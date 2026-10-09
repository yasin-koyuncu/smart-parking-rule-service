package com.parkview.ruleengine.dto.event;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Consumed: {@code payment.completed.fine} on exchange {@code parkview.payment}, published by payment-service.
 *
 * @param referenceId id of the paid fine
 */
public record PaymentCompletedEvent(
        String paymentId,
        @NotNull UUID referenceId,
        String referenceType,
        String userId,
        BigDecimal amountSek,
        long timestamp
) {
}
