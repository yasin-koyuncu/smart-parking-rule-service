package com.parkview.ruleengine.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A permit granted to a plate")
public record PermitResponse(
        UUID id,
        @Schema(example = "ABC123") String plate,
        @Schema(example = "resident") String permitType,
        @Schema(description = "Zone the permit is valid in; null means every zone") String zoneId,
        Instant validFrom,
        @Schema(description = "End of validity (exclusive); null means unlimited") Instant validTo,
        String createdBy,
        Instant createdAt
) {
}
