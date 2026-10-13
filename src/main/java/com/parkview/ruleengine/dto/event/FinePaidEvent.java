package com.parkview.ruleengine.dto.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published: {@code fine.paid} on exchange {@code parkview.violation}, once when a fine goes from unpaid to paid
 * (online payment event or manual payment by an operator); a repeated payment publishes nothing. No free text, no actor.
 *
 * @param method {@code online | cash | transfer | other}
 */
public record FinePaidEvent(
        UUID fineId,
        String plate,
        String zoneId,
        BigDecimal amountSek,
        String method,
        Instant occurredAt
) {
}
