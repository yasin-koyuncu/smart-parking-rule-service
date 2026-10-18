package com.parkview.ruleengine.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Fine as needed by payment-service to create a checkout")
public record InternalFineResponse(
        UUID id,
        String plate,
        UUID userId,
        String zoneId,
        @Schema(description = "Amount to pay, SEK") BigDecimal amountSek,
        @Schema(example = "SEK") String currency,
        boolean paid,
        @Schema(description = "True when an operator voided the fine; payment-service must not charge it") boolean voided,
        @Schema(description = "True while a dispute is open; payment-service must not start a payment for it") boolean disputed
) {
}
