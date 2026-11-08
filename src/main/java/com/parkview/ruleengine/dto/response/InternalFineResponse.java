package com.parkview.ruleengine.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Fine as needed by payment-service to create a checkout")
public record InternalFineResponse(
        UUID id,
        String plate,
        UUID userId,
        @Schema(description = "Null for a fine at a spot without a zone") String zoneId,
        @Schema(description = "The site of the fine, always set; payment-service lets an operator pay a fine only at one of their sites") String siteId,
        @Schema(description = "Amount to pay, in the currency of the fine (the currency of its site)") BigDecimal amount,
        @Schema(description = "ISO 4217 code of the amount", example = "SEK") String currency,
        boolean paid,
        @Schema(description = "True when an operator voided the fine; payment-service must not charge it") boolean voided,
        @Schema(description = "True while a dispute is open; payment-service must not start a payment for it") boolean disputed
) {
}
