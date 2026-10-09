package com.parkview.ruleengine.mapper;

import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.dto.response.ViolationResponse;

public final class ViolationMapper {

    private ViolationMapper() {
    }

    public static ViolationResponse toResponse(Violation v) {
        return new ViolationResponse(
                v.getId(), v.getSpotId(), v.getZoneId(), v.getPlate(), v.getUserId(), v.getViolationType(),
                v.getDetectedAt(), v.getGraceUntil(), v.getLastSeenAt(), v.getResolvedAt(), v.getResolutionReason(),
                v.getFineIssuedAt(), v.getFineId(), v.getImagePath(), v.getNotifiedAt(), v.getCreatedAt());
    }
}
