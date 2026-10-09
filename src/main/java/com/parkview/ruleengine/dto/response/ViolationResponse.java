package com.parkview.ruleengine.dto.response;

import com.parkview.ruleengine.domain.ResolutionReason;
import com.parkview.ruleengine.domain.ViolationType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A parking violation (open, resolved or fined)")
public record ViolationResponse(
        UUID id,
        @Schema(description = "Spot the vehicle occupies") UUID spotId,
        String zoneId,
        @Schema(description = "Normalised plate", example = "ABC123") String plate,
        @Schema(description = "Owner of the plate when it is registered to a user") UUID userId,
        ViolationType violationType,
        Instant detectedAt,
        @Schema(description = "The fine is issued once this instant has passed and the violation is still open")
        Instant graceUntil,
        @Schema(description = "Last time a detection confirmed the violation") Instant lastSeenAt,
        Instant resolvedAt,
        ResolutionReason resolutionReason,
        Instant fineIssuedAt,
        UUID fineId,
        String imagePath,
        Instant notifiedAt,
        Instant createdAt
) {
}
