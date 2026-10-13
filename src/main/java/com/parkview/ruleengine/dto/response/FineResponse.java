package com.parkview.ruleengine.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A fine as shown to drivers and operators. The owner's user id is never part of it.")
public record FineResponse(
        UUID id,
        @Schema(description = "Normalised plate", example = "ABC123") String plate,
        String zoneId,
        @Schema(description = "Camera that produced the evidence, when known") String cameraId,
        @Schema(description = "Why the fine was issued, as stored (lower case)", example = "wrong_permit") String reason,
        @Schema(description = "Optional machine readable detail written by other producers of the table") String reasonCode,
        @Schema(description = "Optional human readable detail written by other producers of the table") String reasonText,
        @Schema(description = "Amount to pay, SEK") BigDecimal amountSek,
        Instant issuedAt,
        boolean paid,
        String imagePath
) {
}
