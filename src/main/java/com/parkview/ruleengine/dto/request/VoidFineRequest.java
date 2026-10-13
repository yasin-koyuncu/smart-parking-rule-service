package com.parkview.ruleengine.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Voids an unpaid fine")
public record VoidFineRequest(
        @Schema(description = "Why the fine is voided (3 to 300 characters, surrounding spaces are removed). Shown in the audit trail to staff only.",
                example = "Plate misread by the camera")
        @NotBlank @Size(min = 3, max = 300)
        String reason
) {

    public VoidFineRequest {
        reason = reason == null ? null : reason.strip();
    }
}
