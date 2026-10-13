package com.parkview.ruleengine.dto.event;

import java.time.Instant;
import java.util.UUID;

/** Published: {@code fine.voided} on exchange {@code parkview.violation}, once when an operator voids a fine. No free text, no actor. */
public record FineVoidedEvent(
        UUID fineId,
        String plate,
        String zoneId,
        Instant occurredAt
) {
}
