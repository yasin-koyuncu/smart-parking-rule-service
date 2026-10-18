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
        @Schema(description = "What happened", allowableValues = {"void", "mark_paid", "paid_online", "dispute_opened", "dispute_upheld",
                "dispute_voided", "dispute_withdrawn"}) String action,
        @Schema(description = "Free text given by the operator, the dispute message or the decision note; null for the online payment and a withdrawal") String reason,
        @Schema(description = "Structured facts: {} for void, {method} for mark_paid, {method, paymentId} for paid_online, {disputeId, channel} for dispute_opened, {disputeId} for the other dispute actions")
        Map<String, Object> details
) {
}
