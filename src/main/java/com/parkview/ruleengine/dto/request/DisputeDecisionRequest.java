package com.parkview.ruleengine.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "The operator's decision on a dispute")
public record DisputeDecisionRequest(
        @Schema(description = "upheld: the fine stands and can be paid again; voided: the fine is voided", allowableValues = {"upheld", "voided"})
        @NotBlank @Pattern(regexp = "upheld|voided", message = "must be upheld or voided")
        String outcome,
        @Schema(description = "Why (10 to 1000 characters, surrounding spaces are removed). Shown to the driver and in the audit trail; "
                + "for voided it becomes the void reason of the fine.", example = "The permit was valid but not yet registered")
        @NotBlank @Size(min = 10, max = 1000)
        String note
) {

    public DisputeDecisionRequest {
        outcome = outcome == null ? null : outcome.strip().toLowerCase(java.util.Locale.ROOT);
        note = note == null ? null : note.strip();
    }
}
