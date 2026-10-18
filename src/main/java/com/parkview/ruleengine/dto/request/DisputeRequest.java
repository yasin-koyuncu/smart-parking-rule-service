package com.parkview.ruleengine.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Asks for a person to review a fine")
public record DisputeRequest(
        @Schema(description = "Why the fine should be reviewed (10 to 1000 characters, surrounding spaces are removed). Personal text: "
                + "shown to the driver and to staff, never logged.", example = "My car was in the loading zone for a delivery")
        @NotBlank @Size(min = 10, max = 1000)
        String message
) {

    public DisputeRequest {
        message = message == null ? null : message.strip();
    }
}
