package com.parkview.ruleengine.mapper;

import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.FineEvidence;
import com.parkview.ruleengine.dto.response.EvidenceResponse;
import com.parkview.ruleengine.dto.response.ExplanationResponse;
import com.parkview.ruleengine.service.fine.FineExplanation;

import java.util.Locale;
import java.util.UUID;

public final class EvidenceMapper {

    private EvidenceMapper() {
    }

    /** The full evidence for operators, snapshots included; {@code recorded:false} for a fine that has none. */
    public static EvidenceResponse toResponse(UUID fineId, FineEvidence e) {
        if (e == null) {
            return new EvidenceResponse.NotRecorded(false, fineId);
        }
        return new EvidenceResponse.Recorded(true, e.getFineId(), e.getPlate(), e.getZoneId(), e.getZoneAddress(), e.getSiteId(),
                e.getSiteAddress(), e.getSpotId(),
                e.getSpotNumber(), e.getViolationType(), e.getRuleSummary(), e.getGraceMinutes(), e.getViolationDetectedAt(),
                e.getGraceExpiredAt(), e.getIssuedAt(), e.getMinConfidence(), e.getDecisionBasis(), e.getDetectionIds(),
                e.getImagePaths());
    }

    /** The driver's view: plain language only. No snapshot keys, no detection ids, no plate (the driver asked for their own fine). */
    public static ExplanationResponse toExplanation(FineExplanation x) {
        Fine fine = x.fine();
        FineEvidence e = x.evidence();
        return new ExplanationResponse(fine.getId(), fine.getIssuedAt(), fine.getAmountSek(), x.zoneAddress(), x.siteAddress(),
                e == null ? null : e.getSpotNumber(), fine.getReason().name().toLowerCase(Locale.ROOT),
                DisputeMapper.reasonText(fine), e == null ? null : e.getRuleSummary(),
                e == null ? null : e.getViolationDetectedAt(), e == null ? null : e.getGraceMinutes(),
                e == null ? null : e.getGraceExpiredAt(), x.disputeStatus() == null ? null : x.disputeStatus().wire());
    }
}
