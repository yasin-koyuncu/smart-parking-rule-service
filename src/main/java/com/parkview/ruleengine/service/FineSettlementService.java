package com.parkview.ruleengine.service;

import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.FineAction;
import com.parkview.ruleengine.domain.FineActionType;
import com.parkview.ruleengine.domain.PaidMethod;
import com.parkview.ruleengine.repository.FineActionRepository;
import com.parkview.ruleengine.repository.FineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Settles a fine when payment-service reports the online payment ({@code payment.completed.fine}). Like the operator
 * commands in {@link FineManagementService} it locks the fine row, writes the audit row in the same transaction and
 * publishes {@code fine.paid} after commit, only on the actual transition.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FineSettlementService {

    private final FineRepository fines;
    private final FineActionRepository actions;
    private final FineEvents events;
    private final Clock clock;

    /**
     * Idempotent: a redelivered or repeated payment is a no-op. An unknown fine id is logged, not retried. A voided
     * fine stays voided (the charge raced with the void): it is logged for a refund and nothing is published, so the
     * stream never claims a voided fine was paid.
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
            actions.save(FineAction.of(fineId, now, null, FineActionType.PAID_ONLINE, null, auditDetails(paymentId)));
            events.paid(fine, PaidMethod.ONLINE, now);
            log.info("Fine {} marked as paid (online)", fineId);
        }
    }

    private static Map<String, Object> auditDetails(String paymentId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("method", PaidMethod.ONLINE.wire());
        if (paymentId != null) {
            details.put("paymentId", paymentId);
        }
        return details;
    }
}
