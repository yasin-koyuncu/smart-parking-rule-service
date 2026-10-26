package com.parkview.ruleengine.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Records that an unpaid fine was paid outside the online flow")
public record MarkFinePaidRequest(
        @Schema(description = "Why it is marked by hand (3 to 300 characters, surrounding spaces are removed). Shown in the audit trail to staff only.",
                example = "Paid at the counter, receipt 4711")
        @NotBlank @Size(min = 3, max = 300)
        String reason,

        @Schema(description = "How it was paid", allowableValues = {"cash", "transfer", "other"}, example = "cash")
        @NotBlank @Pattern(regexp = "^(cash|transfer|other)$", message = "{validation.paidMethod}")
        String method
) {

    public MarkFinePaidRequest {
        reason = reason == null ? null : reason.strip();
        method = method == null ? null : method.strip();
    }
}
