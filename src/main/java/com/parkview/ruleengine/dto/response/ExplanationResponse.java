package com.parkview.ruleengine.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** What a driver may see about why a fine was issued: plain language, never snapshots or detection ids (they can show other people's cars). */
@Schema(description = "Plain-language explanation of a fine; no snapshots and no detection ids")
public record ExplanationResponse(
        UUID fineId,
        Instant issuedAt,
        BigDecimal amountSek,
        @Schema(description = "Street address of the zone, when known; null for a fine at a spot without a zone") String zoneAddress,
        @Schema(description = "Street address of the site, when known") String siteAddress,
        @Schema(description = "Spot number, when known") String spotNumber,
        @Schema(description = "Why the fine was issued, as stored (lower case)", example = "overstay") String reasonCode,
        @Schema(description = "Plain-language text of why the fine was issued") String reasonText,
        @Schema(description = "Plain-language text of the rule that applied; null for fines issued before evidence was recorded") String ruleSummary,
        @Schema(description = "When the violation was first detected; null when not recorded") Instant detectedAt,
        @Schema(description = "Minutes the driver had to correct it; null when not recorded") Integer graceMinutes,
        @Schema(description = "When the grace period ended; null when not recorded") Instant graceExpiredAt,
        @Schema(description = "Status of the latest dispute; null when there never was one",
                allowableValues = {"open", "upheld", "voided", "withdrawn"}) String disputeStatus
) {
}
