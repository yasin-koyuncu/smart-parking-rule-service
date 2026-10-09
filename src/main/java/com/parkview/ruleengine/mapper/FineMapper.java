package com.parkview.ruleengine.mapper;

import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.dto.response.InternalFineResponse;

public final class FineMapper {

    public static final String CURRENCY = "SEK";

    private FineMapper() {
    }

    public static InternalFineResponse toInternalResponse(Fine f) {
        return new InternalFineResponse(f.getId(), f.getPlate(), f.getUserId(), f.getZoneId(),
                f.getAmountSek(), CURRENCY, f.isPaid());
    }
}
