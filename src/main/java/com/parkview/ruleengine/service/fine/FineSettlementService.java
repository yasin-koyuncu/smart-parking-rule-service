package com.parkview.ruleengine.service.fine;

import com.parkview.ruleengine.domain.type.DisputeStatus;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.FineAction;
import com.parkview.ruleengine.domain.type.FineActionType;
import com.parkview.ruleengine.domain.type.PaidMethod;
import com.parkview.ruleengine.engine.EngineMetrics;
import com.parkview.ruleengine.repository.FineActionRepository;
import com.parkview.ruleengine.repository.FineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Settles a fine when payment-service reports the online payment ({@code payment.completed.fine}). Like the operator
 * commands in {@link FineManagementService} it locks the fine row, writes the audit row and the {@code fine.paid} outbox
 * event in the same transaction, only on the actual transition. A payment that completes for a fine whose dispute is open
 * (payment-service refuses to start one, so this is a race) is never refused: the fine stays paid and the system closes
 * the dispute as upheld with a note.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FineSettlementService {

    static final String VOIDED = "voided";
    static final String ALREADY_PAID = "already_paid";
    static final String AMOUNT_MISMATCH = "amount_mismatch";
    static final String UNKNOWN_FINE = "unknown_fine";
    static final String PAID_WHILE_DISPUTED_NOTE = "Closed automatically: the fine was paid while the dispute was open.";

    private final FineRepository fines;
    private final FineActionRepository actions;
    private final DisputeCloser disputeCloser;
    private final FineEvents events;
    private final EngineMetrics metrics;
    private final Clock clock;

    /** Same without the paid amount: nothing to compare it with. */
    @Transactional
    public void markPaidOnline(UUID fineId, String paymentId) {
        markPaidOnline(fineId, paymentId, null);
    }

    /**
     * Idempotent: a redelivered or repeated payment is a no-op. An unknown fine id is logged, not retried. A voided
     * fine stays voided (the charge raced with the void) and nothing is published, so the stream never claims a voided
     * fine was paid.
     *
     * <p>Money was taken in every one of these cases, so the ones a person has to act on leave an audit row
     * {@code payment_needs_review} (and count in {@code ruleengine.payments.needs_review}) instead of only a log line: the
     * fine is voided, or already paid (by hand, or by another payment), or the paid amount is not the amount of the fine. The
     * row is written once per payment, so a redelivery adds nothing. A first payment of another amount still settles an
     * unpaid fine: it is flagged, not refused.
     *
     * @param amountSek the amount payment-service charged, null when the event did not say
     */
    @Transactional
    public void markPaidOnline(UUID fineId, String paymentId, BigDecimal amountSek) {
        Fine fine = fines.findByIdForUpdate(fineId).orElse(null);
        if (fine == null) {
            log.warn("payment.completed.fine for unknown fine {}", fineId);
            metrics.paymentNeedsReview(UNKNOWN_FINE);
            return;
        }
        Instant now = clock.instant();
        List<String> causes = new ArrayList<>();
        boolean settles = false;
        if (fine.isVoided()) {
            log.warn("Payment {} completed for fine {} which is voided; it stays voided, the payment needs a refund", paymentId, fineId);
            causes.add(VOIDED);
        } else if (fine.isPaid()) {
            // the same payment delivered again changes nothing; any other payment for a paid fine was charged twice
            if (!actions.existsForPayment(fineId, FineActionType.PAID_ONLINE.wire(), paymentId)) {
                log.warn("Payment {} completed for fine {} which is already paid; the payment needs a refund", paymentId, fineId);
                causes.add(ALREADY_PAID);
            }
        } else {
            settles = true;
        }
        if (amountSek != null && (settles || !causes.isEmpty()) && amountSek.compareTo(fine.getAmountSek()) != 0) {
            log.warn("Payment {} for fine {} has another amount than the fine", paymentId, fineId);
            causes.add(AMOUNT_MISMATCH);
        }

        if (settles && fine.markPaid(now, PaidMethod.ONLINE, null)) {
            actions.save(FineAction.of(fineId, now, null, FineActionType.PAID_ONLINE, null, auditDetails(paymentId)));
            events.paid(fine, PaidMethod.ONLINE, now);
            if (disputeCloser.closeOpen(fine, DisputeStatus.UPHELD, null, PAID_WHILE_DISPUTED_NOTE, now).isPresent()) {
                log.warn("Fine {} was paid while a dispute was open; the dispute is closed as upheld by the system", fineId);
            }
            log.info("Fine {} marked as paid (online)", fineId);
        }
        if (!causes.isEmpty()
                && !actions.existsForPayment(fineId, FineActionType.PAYMENT_NEEDS_REVIEW.wire(), paymentId)) {
            actions.save(FineAction.of(fineId, now, null, FineActionType.PAYMENT_NEEDS_REVIEW, reasonOf(causes),
                    reviewDetails(paymentId, causes, amountSek, fine.getAmountSek())));
            causes.forEach(metrics::paymentNeedsReview);
        }
    }

    private static String reasonOf(List<String> causes) {
        return "Payment completed but needs review: " + String.join(", ", causes.stream().map(c -> c.replace('_', ' ')).toList());
    }

    private static Map<String, Object> reviewDetails(String paymentId, List<String> causes, BigDecimal paid, BigDecimal fineAmount) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (paymentId != null) {
            details.put("paymentId", paymentId);
        }
        details.put("causes", List.copyOf(causes));
        if (paid != null) {
            details.put("paidAmountSek", paid.toPlainString());
        }
        details.put("fineAmountSek", fineAmount.toPlainString());
        return details;
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
