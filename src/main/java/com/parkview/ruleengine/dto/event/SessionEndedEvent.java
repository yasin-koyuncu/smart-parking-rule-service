package com.parkview.ruleengine.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * Consumed: {@code session.ended} on exchange {@code parkview.session}, published by session-service when a parking
 * session ends (the vehicle left, or an operator ended it). Only the fields rule-service needs are read; the others
 * (session id, cost, ...) are ignored. A session needs at least one of {@code siteId} and {@code zoneId}.
 *
 * @param plate      plate of the session
 * @param zoneId     zone of the session, null for a session at a place of the site without a zone (ADR 0009)
 * @param siteId     site of the session; null from a producer that predates sites, then the zone's site is used
 * @param endedAt    when the session ended; falls back to {@code occurredAt} when absent
 * @param occurredAt when the event was produced
 */
public record SessionEndedEvent(
        @NotBlank String plate,
        String zoneId,
        String siteId,
        Instant endedAt,
        Instant occurredAt
) {

    /** Same without a site, as published before ADR 0009. */
    public SessionEndedEvent(String plate, String zoneId, Instant endedAt, Instant occurredAt) {
        this(plate, zoneId, null, endedAt, occurredAt);
    }

    /** When the vehicle was last known to be there: the end of the session, else the time of the event, else unknown (null). */
    public Instant endTime() {
        return endedAt != null ? endedAt : occurredAt;
    }

    /** The session must say where it was: its site, or a zone (whose site is then used). */
    @JsonIgnore
    @AssertTrue(message = "{validation.siteOrZone}")
    public boolean isPlaced() {
        return (siteId != null && !siteId.isBlank()) || (zoneId != null && !zoneId.isBlank());
    }
}
