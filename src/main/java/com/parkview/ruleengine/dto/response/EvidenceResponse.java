package com.parkview.ruleengine.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The evidence of a fine for operators: everything that was recorded when it was issued, or the fact that nothing was. */
@Schema(description = "Frozen evidence of a fine, or {recorded:false} for a fine issued before evidence was recorded",
        oneOf = {EvidenceResponse.Recorded.class, EvidenceResponse.NotRecorded.class})
public sealed interface EvidenceResponse {

    boolean recorded();

    UUID fineId();

    @Schema(name = "EvidenceRecorded")
    record Recorded(
            boolean recorded,
            UUID fineId,
            String plate,
            String zoneId,
            String zoneAddress,
            UUID spotId,
            String spotNumber,
            @Schema(description = "Upper-case violation type", example = "OVERSTAY") String violationType,
            @Schema(description = "Plain-language text of the rule that applied; fixed when the fine was issued") String ruleSummary,
            int graceMinutes,
            Instant violationDetectedAt,
            Instant graceExpiredAt,
            Instant issuedAt,
            @Schema(description = "Lowest detector confidence among the detections involved; null when none had one") BigDecimal minConfidence,
            @Schema(description = "How the decision was taken", allowableValues = {"automatic"}) String decisionBasis,
            @Schema(description = "Ids of the detections involved (ingestion-service)") List<String> detectionIds,
            @Schema(description = "Storage keys of the camera snapshots, like imagePath of a fine; they can show other people's cars")
            List<String> imagePaths
    ) implements EvidenceResponse {
    }

    @Schema(name = "EvidenceNotRecorded")
    record NotRecorded(boolean recorded, UUID fineId) implements EvidenceResponse {
    }
}
