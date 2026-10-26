package com.parkview.ruleengine.dto.event;

import com.parkview.ruleengine.domain.type.ViolationType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Published: {@code fine.issued} on exchange {@code parkview.violation} (formerly {@code violation.expired}).
 *
 * @param zoneId        null for a fine at a spot without a zone (ADR 0009)
 * @param zoneAddress   street address of the zone, null without a zone or when it has none
 * @param siteId        always set
 * @param siteAddress   street address of the site, null when it has none
 * @param violationType type of the violation the fine was issued for (upper-case enum name)
 */
public record FineIssuedEvent(
        UUID fineId,
        UUID violationId,
        String plate,
        UUID userId,
        String zoneId,
        String zoneAddress,
        String siteId,
        String siteAddress,
        BigDecimal amountSek,
        long timestamp,
        ViolationType violationType
) {
}
