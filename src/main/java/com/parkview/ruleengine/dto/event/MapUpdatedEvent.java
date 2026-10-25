package com.parkview.ruleengine.dto.event;

import jakarta.validation.constraints.NotBlank;

/**
 * Consumed: {@code map.updated} on exchange {@code parkview.mapping}, published by parking-mapping-service after a
 * committed change of the map of a zone (spots, views, rollback, and also zone rules). Only the zone is used: it tells
 * rule-service to forget the spots it cached for it.
 *
 * @param timestamp epoch milliseconds
 */
public record MapUpdatedEvent(
        @NotBlank String zoneId,
        int versionNumber,
        int spotCount,
        String changeReason,
        long timestamp
) {
}
