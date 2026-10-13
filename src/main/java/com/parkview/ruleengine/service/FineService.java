package com.parkview.ruleengine.service;

import com.parkview.ruleengine.client.PlateOwnerLookup;
import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.config.RabbitConfig;
import com.parkview.ruleengine.config.RuleEngineProperties;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.FineAction;
import com.parkview.ruleengine.domain.FineActionType;
import com.parkview.ruleengine.domain.PaidMethod;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.dto.event.FineIssuedEvent;
import com.parkview.ruleengine.dto.event.FinePaidEvent;
import com.parkview.ruleengine.dto.event.FineVoidedEvent;
import com.parkview.ruleengine.messaging.EventPublisher;
import com.parkview.ruleengine.repository.FineActionRepository;
import com.parkview.ruleengine.repository.FineRepository;
import com.parkview.ruleengine.web.ConflictException;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Issues fines for expired violations, records their payment and lets operators void them or mark them as paid.
 *
 * <p>Every command locks the fine row first and decides on its current state, so a void, a manual payment and the
 * online payment event never interleave. A command writes its {@code fine_actions} audit row in the same transaction;
 * its event is published after commit and only on the actual transition (a repeat changes nothing and says nothing).
 * The free text {@code reason} is stored but never logged and never part of an event.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FineService {

    private final FineRepository fines;
    private final FineActionRepository actions;
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

    /**
     * {@code payment.completed.fine}: idempotent, a redelivered or repeated payment is a no-op. An unknown fine id is
     * logged, not retried. A voided fine stays voided (the charge raced with the void): it is logged for a refund and
     * nothing is published, so the stream never claims a voided fine was paid.
     */
    @Transactional
    public void markPaidOnline(UUID fineId, String paymentId) {
        Fine fine = fines.findByIdForUpdate(fineId).orElse(null);
        if (fine == null) {
            log.warn("payment.completed.fine for unknown fine {}", fineId);
            return;
        }
        if (fine.isVoided()) {
            log.warn("Payment {} completed for fine {} which is voided; it stays voided, the payment needs a refund", paymentId, fineId);
            return;
        }
        Instant now = clock.instant();
        if (fine.markPaid(now, PaidMethod.ONLINE, null)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("method", PaidMethod.ONLINE.wire());
            if (paymentId != null) {
                details.put("paymentId", paymentId);
            }
            actions.save(FineAction.of(fineId, now, null, FineActionType.PAID_ONLINE, null, details));
            publishPaid(fine, PaidMethod.ONLINE, now);
            log.info("Fine {} marked as paid (online)", fineId);
        }
    }

    /**
     * Operator command: voids an unpaid fine. Idempotent: an already voided fine is returned unchanged.
     *
     * @param reason free text, stored in the fine and the audit trail only
     * @param actor  the operator's user id (token subject)
     * @throws ResourceNotFoundException when the id is unknown
     * @throws ConflictException         when the fine is paid (a refund is a separate flow)
     */
    @Transactional
    public Fine voidFine(UUID fineId, String reason, UUID actor) {
        Fine fine = lock(fineId);
        if (fine.isVoided()) {
            return fine;
        }
        if (fine.isPaid()) {
            throw new ConflictException("A paid fine cannot be voided; refunds are a separate process");
        }
        Instant now = clock.instant();
        fine.voidFine(now, reason, actor);
        actions.save(FineAction.of(fineId, now, actor, FineActionType.VOID, reason, Map.of()));
        events.publish(RabbitConfig.VIOLATION_EXCHANGE, RabbitConfig.FINE_VOIDED_KEY,
                new FineVoidedEvent(fineId, fine.getPlate(), fine.getZoneId(), now));
        log.info("Fine {} voided by {}", fineId, actor);
        return fine;
    }

    /**
     * Operator command: records a payment made outside the online flow. Idempotent: an already paid fine is returned
     * unchanged (whatever method it was paid with).
     *
     * @param method one of the manual methods (cash, transfer, other)
     * @throws ResourceNotFoundException when the id is unknown
     * @throws ConflictException         when the fine is voided
     */
    @Transactional
    public Fine markPaidManually(UUID fineId, String reason, PaidMethod method, UUID actor) {
        if (method == PaidMethod.ONLINE) {
            throw new IllegalArgumentException("online payments come from payment-service");
        }
        Fine fine = lock(fineId);
        if (fine.isPaid()) {
            return fine;
        }
        if (fine.isVoided()) {
            throw new ConflictException("A voided fine cannot be marked as paid");
        }
        Instant now = clock.instant();
        fine.markPaid(now, method, actor);
        actions.save(FineAction.of(fineId, now, actor, FineActionType.MARK_PAID, reason, Map.of("method", method.wire())));
        publishPaid(fine, method, now);
        log.info("Fine {} marked as paid ({}) by {}", fineId, method.wire(), actor);
        return fine;
    }

    private void publishPaid(Fine fine, PaidMethod method, Instant now) {
        events.publish(RabbitConfig.VIOLATION_EXCHANGE, RabbitConfig.FINE_PAID_KEY,
                new FinePaidEvent(fine.getId(), fine.getPlate(), fine.getZoneId(), fine.getAmountSek(), method.wire(), now));
    }

    private Fine lock(UUID fineId) {
        return fines.findByIdForUpdate(fineId).orElseThrow(() -> new ResourceNotFoundException("Fine", fineId));
    }

    @Transactional(readOnly = true)
    public Fine get(UUID fineId) {
        return fines.findById(fineId).orElseThrow(() -> new ResourceNotFoundException("Fine", fineId));
    }

    /** Audit trail of a fine; the caller passes the sort (newest first). */
    @Transactional(readOnly = true)
    public Page<FineAction> history(UUID fineId, Pageable pageable) {
        return actions.findByFineId(fineId, pageable);
    }

    /** @param includeVoided false (the default of the API) leaves out voided fines */
    @Transactional(readOnly = true)
    public Page<Fine> byPlate(String plate, boolean includeVoided, Pageable pageable) {
        return includeVoided ? fines.findByPlate(plate, pageable) : fines.findByPlateAndVoidedAtIsNull(plate, pageable);
    }

    /**
     * @param paid          {@code null} for all fines of the zone, otherwise only the paid or only the unpaid ones
     * @param includeVoided false (the default of the API) leaves out voided fines
     */
    @Transactional(readOnly = true)
    public Page<Fine> byZone(String zoneId, Boolean paid, boolean includeVoided, Pageable pageable) {
        if (paid == null) {
            return includeVoided ? fines.findByZoneId(zoneId, pageable) : fines.findByZoneIdAndVoidedAtIsNull(zoneId, pageable);
        }
        return includeVoided
                ? fines.findByZoneIdAndPaid(zoneId, paid, pageable)
                : fines.findByZoneIdAndPaidAndVoidedAtIsNull(zoneId, paid, pageable);
    }
}
