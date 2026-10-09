package com.parkview.ruleengine.dto.event;

import com.parkview.ruleengine.domain.ViolationType;

import java.util.UUID;

/** Published: {@code violation.created} on exchange {@code parkview.violation}. */
public record ViolationCreatedEvent(
        UUID violationId,
        String plate,
        String zoneId,
        String zoneAddress,
        String cameraId,
        UUID userId,
        UUID spotId,
        String spotNumber,
        ViolationType violationType,
        int graceMinutes,
        long timestamp
) {
}
