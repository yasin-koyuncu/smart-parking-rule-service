package com.parkview.ruleengine.mapper;

import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.dto.response.ViolationResponse;

public final class ViolationMapper {

    private ViolationMapper() {
    }

    /** @param withImagePath false for a caller without {@code violation:read} (a driver): the camera snapshot key is staff evidence */
    public static ViolationResponse toResponse(Violation v, boolean withImagePath) {
        return new ViolationResponse(
                v.getId(), v.getSpotId(), v.getZoneId(), v.getSiteId(), v.getPlate(), v.getUserId(), v.getViolationType(),
                v.getDetectedAt(), v.getGraceUntil(), v.getLastSeenAt(), v.getResolvedAt(), v.getResolutionReason(),
                v.getFineIssuedAt(), v.getFineId(), withImagePath ? v.getImagePath() : null, v.getNotifiedAt(), v.getCreatedAt());
    }
}
