package com.parkview.ruleengine.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;

/**
 * Consumed: {@code map.updated} on exchange {@code parkview.mapping}, published by parking-mapping-service after a
 * committed change of the map of a site (spots, views, rollback), and also after a change of its zone rules, site rules or
 * tariff (ADR 0009: the event is per site). Only the place is used: it tells rule-service to forget the spots and rules it
 * cached for it. An event needs at least one of {@code siteId} and {@code zoneId}.
 *
 * @param zoneId    set for a change of a zone rule; the only place named by a producer that predates sites
 * @param siteId    the site whose map changed; null from a producer that predates sites, then the zone's site is used
 * @param timestamp epoch milliseconds
 */
public record MapUpdatedEvent(
        String zoneId,
        String siteId,
        int versionNumber,
        int spotCount,
        String changeReason,
        long timestamp
) {

    /** The event must name the place whose caches are evicted. */
    @JsonIgnore
    @AssertTrue(message = "{validation.siteOrZone}")
    public boolean isPlaced() {
        return (siteId != null && !siteId.isBlank()) || (zoneId != null && !zoneId.isBlank());
    }
}
