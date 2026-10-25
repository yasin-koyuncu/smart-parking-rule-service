package com.parkview.ruleengine.dto.event;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * Consumed: {@code session.ended} on exchange {@code parkview.session}, published by session-service when a parking
 * session ends (the vehicle left, or an operator ended it). Only the fields rule-service needs are read; the others
 * (session id, cost, ...) are ignored.
 *
 * @param plate      plate of the session
 * @param zoneId     zone of the session
 * @param endedAt    when the session ended; falls back to {@code occurredAt} when absent
 * @param occurredAt when the event was produced
 */
public record SessionEndedEvent(
        @NotBlank String plate,
        @NotBlank String zoneId,
        Instant endedAt,
        Instant occurredAt
) {

    /** When the vehicle was last known to be there: the end of the session, else the time of the event, else unknown (null). */
    public Instant endTime() {
        return endedAt != null ? endedAt : occurredAt;
    }
}
