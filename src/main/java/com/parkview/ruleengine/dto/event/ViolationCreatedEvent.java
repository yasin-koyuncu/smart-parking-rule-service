package com.parkview.ruleengine.dto.event;

import com.parkview.ruleengine.domain.type.ViolationType;

import java.util.UUID;

/**
 * Published: {@code violation.created} on exchange {@code parkview.violation}.
 *
 * @param zoneId      the zone of the spot, null for a spot without a zone (ADR 0009)
 * @param zoneAddress street address of the zone, null without a zone or when it has none
 * @param siteId      the site of the spot; always set
 * @param siteAddress street address of the site, null when it has none
 */
public record ViolationCreatedEvent(
        UUID violationId,
        String plate,
        String zoneId,
        String zoneAddress,
        String siteId,
        String siteAddress,
        String cameraId,
        UUID userId,
        UUID spotId,
        String spotNumber,
        ViolationType violationType,
        int graceMinutes,
        long timestamp
) {
}
