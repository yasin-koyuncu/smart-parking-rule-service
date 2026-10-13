package com.parkview.ruleengine.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Schema(description = "One entry of the audit trail of a fine")
public record FineActionResponse(
        UUID id,
        Instant at,
        @Schema(description = "User who acted; null for the online payment") UUID actorId,
        @Schema(description = "What happened", allowableValues = {"void", "mark_paid", "paid_online"}) String action,
        @Schema(description = "Free text given by the operator; null for the online payment") String reason,
        @Schema(description = "Structured facts: {} for void, {method} for mark_paid, {method, paymentId} for paid_online")
        Map<String, Object> details
) {
}
