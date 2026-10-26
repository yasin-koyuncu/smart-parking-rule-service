package com.parkview.ruleengine.dto.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published: {@code fine.dispute.decided} on exchange {@code parkview.violation}, once when a dispute is closed. No actor.
 *
 * @param zoneId       null for a fine at a spot without a zone (ADR 0009)
 * @param siteId       always set
 * @param userId       the owner of the plate when known, like {@code fine.issued}
 * @param outcome      {@code upheld | voided | withdrawn}
 * @param decisionNote the motivation an operator wrote (null for a withdrawal); the driver is meant to read it, so the
 *                     notification service puts it in the in-app notification. It is not part of the stream event.
 */
public record FineDisputeDecidedEvent(
        UUID disputeId,
        UUID fineId,
        String plate,
        String zoneId,
        String siteId,
        UUID userId,
        String outcome,
        Instant occurredAt,
        String decisionNote
) {
}
