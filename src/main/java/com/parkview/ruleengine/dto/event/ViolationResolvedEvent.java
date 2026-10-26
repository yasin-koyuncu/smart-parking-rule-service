package com.parkview.ruleengine.dto.event;

import com.parkview.ruleengine.domain.type.ResolutionReason;

import java.time.Instant;
import java.util.UUID;

/**
 * Published: {@code violation.resolved} on exchange {@code parkview.violation}.
 *
 * @param zoneId null for a violation at a spot without a zone (ADR 0009)
 * @param siteId always set
 */
public record ViolationResolvedEvent(
        UUID violationId,
        String plate,
        String zoneId,
        String siteId,
        ResolutionReason reason,
        Instant occurredAt
) {
}
