package com.parkview.ruleengine.service.violation;

import com.parkview.ruleengine.client.PlateOwnerDirectory;
import com.parkview.ruleengine.client.SiteDirectory;
import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.config.RabbitConfig;
import com.parkview.ruleengine.config.RuleEngineProperties;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.type.ResolutionReason;
import com.parkview.ruleengine.domain.type.ViolationType;
import com.parkview.ruleengine.domain.value.ParkingSpot;
import com.parkview.ruleengine.dto.event.ViolationCreatedEvent;
import com.parkview.ruleengine.dto.event.ViolationResolvedEvent;
import com.parkview.ruleengine.engine.EngineMetrics;
import com.parkview.ruleengine.repository.FineRepository;
import com.parkview.ruleengine.repository.ViolationRepository;
import com.parkview.ruleengine.service.cache.SpotCacheService;
import com.parkview.ruleengine.service.outbox.OutboxService;
import com.parkview.ruleengine.util.Plates;
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

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Lifecycle of violations: open, keep alive while the vehicle is re-confirmed, resolve. Fining is in
 * {@link FineIssuingService} and the scheduled jobs in {@link ViolationJobs}.
 *
 * <p>Every state change runs in its own short transaction (a failing row never blocks the others)
 * and its event is written to the outbox in that same transaction.
 *
 * <p>A violation belongs to the site of its spot and, when the spot has one, to its zone (ADR 0009): a spot without a zone is
 * controlled like one in a zone, with the rules of its site.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViolationService {

    private static final int STALE_BATCH = 200;

    /** PostgreSQL SQLSTATE of a foreign key violation. */
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private final ViolationRepository violations;
    private final FineRepository fines;
    private final PlateOwnerDirectory plateOwners;
    private final ZoneDirectory zones;
    private final SiteDirectory sites;
    private final OutboxService outbox;
    private final RuleEngineProperties properties;
    private final EngineMetrics metrics;
    private final SpotCacheService spotCache;
    private final Clock clock;
    private final TransactionTemplate tx;

    /**
     * The vehicle was just seen breaking {@code type} in {@code spot}: opens a violation, or confirms
     * the existing one. A concurrent insert that loses against the partial unique index is "already exists".
     */
    public void confirm(ParkingSpot spot, ViolationType type, String plate, String cameraId) {
        confirm(spot, type, plate, cameraId, null, clock.instant());
    }

    /**
     * Same, remembering the plain-language text of the rule that decided it when a new violation is opened (it ends up in
     * the evidence of the fine).
     *
     * @param observedAt when the vehicle was seen (the event time, never later than now): it is the start of the grace period
     *                   and the {@code last_seen_at} of the violation, so a backlog never gives an old sighting a fresh grace
     */
    public void confirm(ParkingSpot spot, ViolationType type, String plate, String cameraId, String ruleSummary,
                        Instant observedAt) {
        try {
            tx.executeWithoutResult(status -> confirmOrOpen(spot, type, plate, cameraId, ruleSummary, observedAt));
        } catch (DataIntegrityViolationException e) {
            if (isForeignKeyViolation(e)) {
                // The spot was deleted after the cache loaded it, e.g. by a re-scan: not an error, and a retry would only
                // dead-letter the message. Plates are personal data and stay out of the log.
                spotCache.evict(spot.siteId());
                log.debug("Spot {} of site {} no longer exists: observation dropped, spot cache evicted", spot.id(), spot.siteId());
                return;
            }
            if (violations.findActive(plate, spot.id(), type).isEmpty()) {
                throw e;
            }
            log.debug("Violation {} for spot {} was opened concurrently", type, spot.id());
        }
    }

    private static boolean isForeignKeyViolation(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql && FOREIGN_KEY_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private void confirmOrOpen(ParkingSpot spot, ViolationType type, String plate, String cameraId, String ruleSummary,
                               Instant observedAt) {
        var active = violations.findActive(plate, spot.id(), type);
        if (active.isPresent()) {
            touchIfDue(active.get(), observedAt);
            return;
        }
        // A vehicle that was already fined and is still there must not collect a second fine.
        List<UUID> fined = violations.findRecentlyFinedIds(
                plate, spot.id(), type, observedAt.minus(properties.staleAfter()), PageRequest.of(0, 1));
        if (!fined.isEmpty()) {
            violations.touch(fined.get(0), observedAt);
            return;
        }
        // The same stay must not be fined twice when the spot was replaced meanwhile (re-scan, "empty the area", rollback): the
        // new spot has a new id and the cascade took the old violation with it, so only the fine itself remembers it. A voided
        // fine counts too: an operator decided about this stay already.
        if (properties.refineBlockHours() > 0 && finedRecently(spot, type, plate, observedAt.minus(properties.refineBlock()))) {
            log.debug("A fine for {} at site {} zone {} was issued within {} h: no new violation", type, spot.siteId(), spot.zoneId(),
                    properties.refineBlockHours());
            return;
        }
        open(spot, type, plate, cameraId, ruleSummary, observedAt);
    }

    /** A fine for the reason in the zone of the spot, or, for a spot without a zone, at its site outside the zones. */
    private boolean finedRecently(ParkingSpot spot, ViolationType type, String plate, Instant since) {
        return spot.zoneId() != null
                ? fines.existsByPlateAndZoneIdAndReasonAndIssuedAtGreaterThanEqual(plate, spot.zoneId(), type, since)
                : fines.existsByPlateAndSiteIdAndZoneIdIsNullAndReasonAndIssuedAtGreaterThanEqual(plate, spot.siteId(), type, since);
    }

    private void touchIfDue(Violation violation, Instant now) {
        Duration sinceLastWrite = Duration.between(violation.getLastSeenAt(), now);
        if (sinceLastWrite.compareTo(properties.touchInterval()) >= 0) {
            violations.touch(violation.getId(), now);
        }
    }

    private void open(ParkingSpot spot, ViolationType type, String plate, String cameraId, String ruleSummary, Instant now) {
        UUID owner = plateOwners.ownerOf(plate).orElse(null);
        int graceMinutes = properties.graceMinutesFor(spot, type);
        Violation violation = violations.saveAndFlush(Violation.open(spot.id(), spot.siteId(), spot.zoneId(), plate, owner, type, now,
                now.plus(Duration.ofMinutes(graceMinutes)), ruleSummary));

        String zoneAddress = spot.zoneId() == null ? null : zones.addressOf(spot.zoneId()).orElse(null);
        outbox.enqueue(OutboxService.AGGREGATE_VIOLATION, violation.getId(), RabbitConfig.VIOLATION_EXCHANGE,
                RabbitConfig.VIOLATION_CREATED_KEY, new ViolationCreatedEvent(violation.getId(), plate, spot.zoneId(), zoneAddress,
                        spot.siteId(), sites.addressOf(spot.siteId()).orElse(null), cameraId, owner, spot.id(),
                        spot.spotNumber(), type, graceMinutes, now.toEpochMilli()));
        metrics.violationCreated(type);
        log.info("Violation {} opened: {} for plate {} at site {} zone {}, grace {} min",
                violation.getId(), type, Plates.mask(plate), spot.siteId(), spot.zoneId(), graceMinutes);
    }

    /**
     * The plate was just observed at site {@code siteId} (in zone {@code zoneId} when the camera has one); every open violation
     * of it there other than {@code keepSpotId}/{@code keepType} no longer describes where it is and is resolved.
     * Pass null for both to resolve all of them.
     *
     * @param zoneId the zone of the observation, null to look at the whole site
     */
    public void resolveOthers(String plate, String siteId, String zoneId, UUID keepSpotId, ViolationType keepType,
                              ResolutionReason reason) {
        for (Violation open : activeOf(plate, siteId, zoneId)) {
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

    /** The open violations of the plate at the site, only those in the zone when {@code zoneId} is set. */
    private List<Violation> activeOf(String plate, String siteId, String zoneId) {
        return zoneId != null
                ? violations.findActiveByPlateAndSiteAndZone(plate, siteId, zoneId)
                : violations.findActiveByPlateAndSite(plate, siteId);
    }

    /**
     * The session of the plate at the site (in the zone, when it had one) ended (session-service): the vehicle has left, so its
     * open violations there are resolved as LEFT, and none of them is fined. Idempotent: a redelivered event finds nothing open. A violation that already
     * has a fine stays as it is.
     *
     * <p>A violation that a detection confirmed after {@code endedAt} is left alone: the vehicle is still there (the driver
     * ended the session without leaving, or the event arrived late). Should a clock difference between the services make
     * this skip a violation that should have been resolved, the fine sweep still refuses it as not recently seen.
     *
     * @param siteId  the site of the session; null from a producer that predates sites, then the zone's site is used
     * @param zoneId  the zone of the session, null for a session outside the zones of the site
     * @param endedAt when the session ended, null when unknown (then every open violation of the plate there is resolved)
     * @return how many violations this call resolved
     */
    public int resolveEnded(String plate, String siteId, String zoneId, Instant endedAt) {
        String site = siteId != null ? siteId : zoneId == null ? null : zones.siteOf(zoneId).orElse(zoneId);
        if (site == null) {
            return 0;
        }
        int resolved = 0;
        for (Violation open : activeOf(plate, site, zoneId)) {
            if (endedAt != null && open.getLastSeenAt().isAfter(endedAt)) {
                continue;
            }
            if (resolve(open, ResolutionReason.LEFT)) {
                resolved++;
            }
        }
        return resolved;
    }

    /**
     * Operator action. Idempotent for an already resolved violation. Not one transaction around the whole method: the
     * atomic update decides, and when it changes nothing the violation is read again, so that a fine issued in between is
     * reported as the conflict it is instead of as a success.
     *
     * @throws ResourceNotFoundException when the id is unknown
     * @throws ConflictException         when a fine was already issued
     */
    public void resolveManually(UUID id) {
        Violation violation = get(id);
        if (violation.getFineIssuedAt() != null) {
            throw ConflictException.coded("violation.fineAlreadyIssued", "A fine has already been issued for this violation");
        }
        if (!resolve(violation, ResolutionReason.MANUAL) && get(id).getFineIssuedAt() != null) {
            throw ConflictException.coded("violation.fineAlreadyIssued", "A fine has already been issued for this violation");
        }
    }

    /** @return true when this call closed the violation, false when it was no longer open (resolved, fined or gone) */
    private boolean resolve(Violation violation, ResolutionReason reason) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            Instant now = clock.instant();
            if (violations.resolveIfActive(violation.getId(), reason, now) == 1) {
                outbox.enqueue(OutboxService.AGGREGATE_VIOLATION, violation.getId(), RabbitConfig.VIOLATION_EXCHANGE,
                        RabbitConfig.VIOLATION_RESOLVED_KEY, new ViolationResolvedEvent(violation.getId(),
                                violation.getPlate(), violation.getZoneId(), violation.getSiteId(), reason, now));
                log.info("Violation {} resolved ({})", violation.getId(), reason);
                return true;
            }
            return false;
        }));
    }

    @Transactional(readOnly = true)
    public Violation get(UUID id) {
        return violations.findById(id).orElseThrow(() -> new ResourceNotFoundException("Violation", id));
    }

    @Transactional(readOnly = true)
    public Page<Violation> activeByZone(String zoneId, Pageable pageable) {
        return violations.findActiveByZone(zoneId, pageable);
    }

    /** The active violations of the site, in its zones and outside them. */
    @Transactional(readOnly = true)
    public Page<Violation> activeBySite(String siteId, Pageable pageable) {
        return violations.findActiveBySite(siteId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Violation> byPlate(String plate, Pageable pageable) {
        return violations.findByPlate(plate, pageable);
    }

    /** Same, limited to the given sites (an operator). No sites, no violations. */
    @Transactional(readOnly = true)
    public Page<Violation> byPlateInSites(String plate, List<String> siteIds, Pageable pageable) {
        if (siteIds.isEmpty()) {
            return Page.empty(pageable);
        }
        return violations.findByPlateAndSiteIdIn(plate, siteIds, pageable);
    }
}
