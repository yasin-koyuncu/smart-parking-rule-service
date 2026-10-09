package com.parkview.ruleengine.mapper;

import com.parkview.ruleengine.domain.PlatePermit;
import com.parkview.ruleengine.dto.response.PermitResponse;

public final class PermitMapper {

    private PermitMapper() {
    }

    public static PermitResponse toResponse(PlatePermit p) {
        return new PermitResponse(p.getId(), p.getPlate(), p.getPermitType(), p.getZoneId(),
                p.getValidFrom(), p.getValidTo(), p.getCreatedBy(), p.getCreatedAt());
    }
}
