package com.parkview.ruleengine.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A request to have a fine reviewed, with what a reader needs to understand it")
public record DisputeResponse(
        UUID id,
        UUID fineId,
        @Schema(description = "Normalised plate", example = "ABC123") String plate,
        @Schema(description = "Zone of the fine; null for a fine at a spot without a zone") String zoneId,
        @Schema(description = "Street address of the zone, when known; null without a zone") String zoneAddress,
        @Schema(description = "Site of the fine") String siteId,
        @Schema(description = "Street address of the site, when known") String siteAddress,
        @Schema(description = "Who asked for the review", allowableValues = {"driver", "operator"}) String channel,
        @Schema(description = "The reason given by the driver (or the operator who took the call)") String message,
        @Schema(allowableValues = {"open", "upheld", "voided", "withdrawn"}) String status,
        @Schema(description = "Amount of the fine, SEK") BigDecimal amountSek,
        @Schema(description = "Why the fine was issued, as stored (lower case)", example = "overstay") String reasonCode,
        @Schema(description = "Plain-language text of why the fine was issued") String reasonText,
        Instant openedAt,
        @Schema(description = "When the dispute was decided or withdrawn; null while open") Instant decidedAt,
        @Schema(description = "The operator's written reason; null while open, after a withdrawal") String decisionNote
) {
}
