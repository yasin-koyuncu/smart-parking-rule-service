package com.parkview.ruleengine.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

@Schema(description = "Grants a permit to a plate")
public record CreatePermitRequest(
        @Schema(description = "Plate; spaces and dashes are ignored", example = "ABC 123")
        @NotBlank @Size(max = 16) @Pattern(regexp = "^[A-Za-z0-9 -]+$", message = "must contain only letters, digits, spaces and dashes")
        String plate,

        @Schema(description = "Permit type as configured on the permit spots (parking_spots.permit_type)", example = "resident")
        @NotBlank @Size(max = 64)
        String permitType,

        @Schema(description = "Zone the permit is valid in; omit for every zone (administrators only)")
        @Size(max = 64)
        String zoneId,

        @Schema(description = "Start of validity; defaults to now")
        Instant validFrom,

        @Schema(description = "End of validity (exclusive); omit for unlimited")
        Instant validTo
) {
}
