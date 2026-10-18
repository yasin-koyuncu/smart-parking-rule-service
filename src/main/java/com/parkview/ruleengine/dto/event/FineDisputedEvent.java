package com.parkview.ruleengine.dto.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published: {@code fine.disputed} on exchange {@code parkview.violation}, once when a dispute is opened. No message text.
 *
 * @param channel {@code driver | operator}
 */
public record FineDisputedEvent(
        UUID disputeId,
        UUID fineId,
        String plate,
        String zoneId,
        String channel,
        Instant occurredAt
) {
}
