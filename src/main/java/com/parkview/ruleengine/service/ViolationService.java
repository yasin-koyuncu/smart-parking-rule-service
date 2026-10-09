package com.parkview.ruleengine.service;

import com.parkview.ruleengine.client.PlateOwnerLookup;
import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.config.RabbitConfig;
import com.parkview.ruleengine.config.RuleEngineProperties;
import com.parkview.ruleengine.domain.ParkingSpot;
import com.parkview.ruleengine.domain.ResolutionReason;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.ViolationType;
import com.parkview.ruleengine.dto.event.ViolationCreatedEvent;
import com.parkview.ruleengine.dto.event.ViolationResolvedEvent;
import com.parkview.ruleengine.messaging.EventPublisher;
import com.parkview.ruleengine.repository.ViolationRepository;
import com.parkview.ruleengine.web.ConflictException;
import com.parkview.ruleengine.web.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Lifecycle of violations: open, keep alive while the vehicle is re-confirmed, resolve. Fining is in
 * {@link FineService} and the scheduled jobs in {@link ViolationJobs}.
 *
 * <p>Every state change runs in its own short transaction (a failing row never blocks the others)
 * and its event is published after that transaction commits.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViolationService {

    private static final int STALE_BATCH = 200;

    private final ViolationRepository violations;
    private final PlateOwnerLookup plateOwners;
    private final ZoneDirectory zones;
    private final EventPublisher events;
    private final RuleEngineProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;

    /**
     * The vehicle was just seen breaking {@code type} in {@code spot}: opens a violation, or confirms
     * the existing one. A concurrent insert that loses against the partial unique index is "already exists".
     */
    public void confirm(ParkingSpot spot, ViolationType type, String plate, String cameraId) {
        Instant now = clock.instant();
        try {
            tx.executeWithoutResult(status -> confirmOrOpen(spot, type, plate, cameraId, now));
        } catch (DataIntegrityViolationException e) {
            if (violations.findActive(plate, spot.id(), type).isEmpty()) {
                throw e;
            }
            log.debug("Violation {} for spot {} was opened concurrently", type, spot.id());
        }
    }

    private void confirmOrOpen(ParkingSpot spot, ViolationType type, String plate, String cameraId, Instant now) {
        var active = violations.findActive(plate, spot.id(), type);
        if (active.isPresent()) {
            touchIfDue(active.get(), now);
            return;
        }
        // A vehicle that was already fined and is still there must not collect a second fine.
        List<UUID> fined = violations.findRecentlyFinedIds(
                plate, spot.id(), type, now.minus(properties.staleAfter()), PageRequest.of(0, 1));
        if (!fined.isEmpty()) {
            violations.touch(fined.get(0), now);
            return;
        }
        open(spot, type, plate, cameraId, now);
    }

    private void touchIfDue(Violation violation, Instant now) {
        Duration sinceLastWrite = Duration.between(violation.getLastSeenAt(), now);
        if (sinceLastWrite.compareTo(properties.touchInterval()) >= 0) {
            violations.touch(violation.getId(), now);
        }
    }

    private void open(ParkingSpot spot, ViolationType type, String plate, String cameraId, Instant now) {
        UUID owner = plateOwners.ownerOf(plate).orElse(null);
        int graceMinutes = graceMinutes(spot, type);
        Violation violation = violations.saveAndFlush(Violation.open(
                spot.id(), spot.zoneId(), plate, owner, type, now, now.plus(Duration.ofMinutes(graceMinutes))));

        events.publish(RabbitConfig.VIOLATION_EXCHANGE, RabbitConfig.VIOLATION_CREATED_KEY,
                new ViolationCreatedEvent(violation.getId(), plate, spot.zoneId(),
                        zones.addressOf(spot.zoneId()).orElse(null), cameraId, owner, spot.id(),
                        spot.spotNumber(), type, graceMinutes, now.toEpochMilli()));
        log.info("Violation {} opened: {} for plate {} in zone {}, grace {} min",
                violation.getId(), type, Plates.mask(plate), spot.zoneId(), graceMinutes);
    }

    private int graceMinutes(ParkingSpot spot, ViolationType type) {
        return switch (type) {
            case WRONG_PERMIT -> spot.permitGraceMin() > 0 ? spot.permitGraceMin() : properties.defaultPermitGraceMinutes();
            case BOUNDARY_EXCEEDED -> spot.boundaryGraceMin() > 0 ? spot.boundaryGraceMin() : properties.defaultBoundaryGraceMinutes();
            default -> properties.defaultBoundaryGraceMinutes();
        };
    }

    /**
     * The plate was just observed in zone {@code zoneId}; every open violation of it other than
     * {@code keepSpotId}/{@code keepType} no longer describes where it is and is resolved.
     * Pass null for both to resolve all of them.
     */
    public void resolveOthers(String plate, String zoneId, UUID keepSpotId, ViolationType keepType, ResolutionReason reason) {
        for (Violation open : violations.findActiveByPlateAndZone(plate, zoneId)) {
            boolean keep = open.getSpotId().equals(keepSpotId) && open.getViolationType() == keepType;
            if (!keep) {
                resolve(open, reason);
            }
        }
    }

    /** Resolves open violations nobody has confirmed for {@code stale-minutes}. */
    public int resolveStale() {
        Instant now = clock.instant();
        List<Violation> stale = violations.findStale(now.minus(properties.staleAfter()), PageRequest.of(0, STALE_BATCH));
        stale.forEach(v -> resolve(v, ResolutionReason.LEFT));
        return stale.size();
    }

    /**
     * Operator action. Idempotent for an already resolved violation.
     *
     * @throws ResourceNotFoundException when the id is unknown
     * @throws ConflictException         when a fine was already issued
     */
    @Transactional
    public void resolveManually(UUID id) {
        Violation violation = get(id);
        if (violation.getFineIssuedAt() != null) {
            throw new ConflictException("A fine has already been issued for this violation");
        }
        resolve(violation, ResolutionReason.MANUAL);
    }

    private void resolve(Violation violation, ResolutionReason reason) {
        tx.executeWithoutResult(status -> {
            Instant now = clock.instant();
            if (violations.resolveIfActive(violation.getId(), reason, now) == 1) {
                events.publish(RabbitConfig.VIOLATION_EXCHANGE, RabbitConfig.VIOLATION_RESOLVED_KEY,
                        new ViolationResolvedEvent(violation.getId(), violation.getPlate(),
                                violation.getZoneId(), reason, now));
                log.info("Violation {} resolved ({})", violation.getId(), reason);
            }
        });
    }

    @Transactional(readOnly = true)
    public Violation get(UUID id) {
        return violations.findById(id).orElseThrow(() -> new ResourceNotFoundException("Violation", id));
    }

    @Transactional(readOnly = true)
    public Page<Violation> activeByZone(String zoneId, Pageable pageable) {
        return violations.findActiveByZone(zoneId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Violation> byPlate(String plate, Pageable pageable) {
        return violations.findByPlate(plate, pageable);
    }
}
