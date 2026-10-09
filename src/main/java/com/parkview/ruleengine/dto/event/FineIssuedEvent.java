package com.parkview.ruleengine.dto.event;

import java.math.BigDecimal;
import java.util.UUID;

/** Published: {@code fine.issued} on exchange {@code parkview.violation} (formerly {@code violation.expired}). */
public record FineIssuedEvent(
        UUID fineId,
        UUID violationId,
        String plate,
        UUID userId,
        String zoneId,
        String zoneAddress,
        BigDecimal amountSek,
        long timestamp
) {
}
