package com.parkview.ruleengine.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A fine as shown to drivers and operators. The owner's user id is never part of it.")
public record FineResponse(
        UUID id,
        @Schema(description = "Normalised plate", example = "ABC123") String plate,
        @Schema(description = "Zone of the fine; null for a fine at a spot without a zone") String zoneId,
        @Schema(description = "Site of the fine") String siteId,
        @Schema(description = "Camera that produced the evidence, when known") String cameraId,
        @Schema(description = "Why the fine was issued, as stored (lower case)", example = "wrong_permit") String reason,
        @Schema(description = "Optional machine readable detail written by other producers of the table") String reasonCode,
        @Schema(description = "Optional human readable detail written by other producers of the table") String reasonText,
        @Schema(description = "Amount to pay, SEK") BigDecimal amountSek,
        Instant issuedAt,
        boolean paid,
        String imagePath,
        @Schema(description = "When it was paid; null while unpaid and for fines paid before this was recorded") Instant paidAt,
        @Schema(description = "True when an operator voided the fine; a voided fine cannot be paid") boolean voided,
        @Schema(description = "When the fine was voided; null when it is not voided") Instant voidedAt,
        @Schema(description = "True while a dispute is open: a person reviews the fine and it cannot be paid meanwhile") boolean disputed,
        @Schema(description = "Status of the latest dispute of the fine; null when there never was one",
                allowableValues = {"open", "upheld", "voided", "withdrawn"}) String disputeStatus
) {
}
