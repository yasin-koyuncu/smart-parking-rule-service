package com.parkview.ruleengine.dto.event;

import com.parkview.ruleengine.domain.type.ViolationType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Published: {@code fine.issued} on exchange {@code parkview.violation} (formerly {@code violation.expired}).
 *
 * @param violationType type of the violation the fine was issued for (upper-case enum name)
 */
public record FineIssuedEvent(
        UUID fineId,
        UUID violationId,
        String plate,
        UUID userId,
        String zoneId,
        String zoneAddress,
        BigDecimal amountSek,
        long timestamp,
        ViolationType violationType
) {
}
