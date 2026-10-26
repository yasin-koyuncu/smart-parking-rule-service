package com.parkview.ruleengine.service.fine;

import com.parkview.ruleengine.domain.type.DisputeStatus;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.FineAction;
import com.parkview.ruleengine.domain.type.FineActionType;
import com.parkview.ruleengine.domain.type.PaidMethod;
import com.parkview.ruleengine.repository.DisputeRepository;
import com.parkview.ruleengine.repository.FineActionRepository;
import com.parkview.ruleengine.repository.FineRepository;
import com.parkview.ruleengine.web.ConflictException;
import com.parkview.ruleengine.web.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Operator commands on a fine: void it, or record a payment made outside the online flow.
 *
 * <p>Every command locks the fine row first and decides on its current state, so a void, a manual payment and the
 * online payment event (see {@link FineSettlementService}) never interleave. A command writes its {@code fine_actions}
 * audit row and its outbox event in the same transaction, and only on the actual transition (a repeat changes nothing and
 * says nothing). The free text {@code reason} is stored but never logged and never part of an event.
 *
 * <p>Disputes: voiding a fine ends its open dispute as voided (the void reason is the decision note); a fine with an open
 * dispute cannot be marked as paid by hand, the dispute has to be decided first.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FineManagementService {

    private final FineRepository fines;
    private final FineActionRepository actions;
    private final DisputeRepository disputes;
    private final DisputeCloser disputeCloser;
    private final FineEvents events;
    private final Clock clock;

    /**
     * Voids an unpaid fine. Idempotent: an already voided fine is returned unchanged.
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
            throw ConflictException.coded("fine.voidPaid", "A paid fine cannot be voided; refunds are a separate process");
        }
        Instant now = clock.instant();
        fine.voidFine(now, reason, actor);
        actions.save(FineAction.of(fineId, now, actor, FineActionType.VOID, reason, Map.of()));
        events.voided(fine, now);
        disputeCloser.closeOpen(fine, DisputeStatus.VOIDED, actor, reason, now);
        log.info("Fine {} voided by {}", fineId, actor);
        return fine;
    }

    /**
     * Records a payment made outside the online flow. Idempotent: an already paid fine is returned unchanged
     * (whatever method it was paid with).
     *
     * @param method one of the manual methods (cash, transfer, other)
     * @throws ResourceNotFoundException when the id is unknown
     * @throws ConflictException         when the fine is voided or has an open dispute
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
            throw ConflictException.coded("fine.payVoided", "A voided fine cannot be marked as paid");
        }
        if (disputes.existsByFineIdAndStatus(fineId, DisputeStatus.OPEN)) {
            throw ConflictException.coded("fine.payUnderReview",
                    "This fine is under review: decide the dispute before marking the fine as paid");
        }
        Instant now = clock.instant();
        fine.markPaid(now, method, actor);
        actions.save(FineAction.of(fineId, now, actor, FineActionType.MARK_PAID, reason, Map.of("method", method.wire())));
        events.paid(fine, method, now);
        log.info("Fine {} marked as paid ({}) by {}", fineId, method.wire(), actor);
        return fine;
    }

    private Fine lock(UUID fineId) {
        return fines.findByIdForUpdate(fineId).orElseThrow(() -> new ResourceNotFoundException("Fine", fineId));
    }
}
