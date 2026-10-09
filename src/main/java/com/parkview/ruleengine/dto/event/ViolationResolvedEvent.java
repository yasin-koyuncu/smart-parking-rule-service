package com.parkview.ruleengine.dto.event;

import com.parkview.ruleengine.domain.ResolutionReason;

import java.time.Instant;
import java.util.UUID;

/** Published: {@code violation.resolved} on exchange {@code parkview.violation}. */
public record ViolationResolvedEvent(
        UUID violationId,
        String plate,
        String zoneId,
        ResolutionReason reason,
        Instant occurredAt
) {
}
