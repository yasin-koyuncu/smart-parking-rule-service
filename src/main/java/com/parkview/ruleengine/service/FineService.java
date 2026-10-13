package com.parkview.ruleengine.service;

import com.parkview.ruleengine.client.PlateOwnerLookup;
import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.config.RabbitConfig;
import com.parkview.ruleengine.config.RuleEngineProperties;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.dto.event.FineIssuedEvent;
import com.parkview.ruleengine.messaging.EventPublisher;
import com.parkview.ruleengine.repository.FineRepository;
import com.parkview.ruleengine.web.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Issues fines for expired violations and records their payment. */
@Slf4j
@Service
@RequiredArgsConstructor
public class FineService {

    private final FineRepository fines;
    private final PlateOwnerLookup plateOwners;
    private final ZoneDirectory zones;
    private final EventPublisher events;
    private final RuleEngineProperties properties;
    private final Clock clock;

    /**
     * Creates the fine for a violation that the caller has locked, links it and publishes
     * {@code fine.issued} after the surrounding transaction commits.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Fine issueFine(Violation violation) {
        Instant now = clock.instant();
        UUID owner = violation.getUserId() != null
                ? violation.getUserId()
                : plateOwners.ownerOf(violation.getPlate()).orElse(null);

        Fine fine = fines.save(Fine.issue(violation, properties.fineAmountFor(violation.getViolationType()), owner, now));
        violation.markFined(fine.getId(), now);

        events.publish(RabbitConfig.VIOLATION_EXCHANGE, RabbitConfig.FINE_ISSUED_KEY,
                new FineIssuedEvent(fine.getId(), violation.getId(), fine.getPlate(), owner, fine.getZoneId(),
                        zones.addressOf(fine.getZoneId()).orElse(null), fine.getAmountSek(), now.toEpochMilli(),
                        violation.getViolationType()));
        log.info("Fine {} issued: {} SEK ({}) for plate {} in zone {}", fine.getId(), fine.getAmountSek(),
                fine.getReason(), Plates.mask(fine.getPlate()), fine.getZoneId());
        return fine;
    }

    /** Idempotent: a redelivered payment event is a no-op. An unknown fine id is logged, not retried. */
    @Transactional
    public void markPaid(UUID fineId) {
        fines.findById(fineId).ifPresentOrElse(fine -> {
            if (fine.markPaid()) {
                log.info("Fine {} marked as paid", fineId);
            }
        }, () -> log.warn("payment.completed.fine for unknown fine {}", fineId));
    }

    @Transactional(readOnly = true)
    public Fine get(UUID fineId) {
        return fines.findById(fineId).orElseThrow(() -> new ResourceNotFoundException("Fine", fineId));
    }

    @Transactional(readOnly = true)
    public Page<Fine> byPlate(String plate, Pageable pageable) {
        return fines.findByPlate(plate, pageable);
    }

    /** @param paid {@code null} for all fines of the zone, otherwise only the paid or only the unpaid ones */
    @Transactional(readOnly = true)
    public Page<Fine> byZone(String zoneId, Boolean paid, Pageable pageable) {
        return paid == null ? fines.findByZoneId(zoneId, pageable) : fines.findByZoneIdAndPaid(zoneId, paid, pageable);
    }
}
