package com.parkview.ruleengine.mapper;

import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.dto.response.FineResponse;
import com.parkview.ruleengine.dto.response.InternalFineResponse;

import java.util.Locale;

public final class FineMapper {

    public static final String CURRENCY = "SEK";

    private FineMapper() {
    }

    public static InternalFineResponse toInternalResponse(Fine f) {
        return new InternalFineResponse(f.getId(), f.getPlate(), f.getUserId(), f.getZoneId(),
                f.getAmountSek(), CURRENCY, f.isPaid(), f.isVoided());
    }

    /** Public view: deliberately without the owner's user id. {@code reason} is the stored lower-case form. */
    public static FineResponse toResponse(Fine f) {
        return new FineResponse(f.getId(), f.getPlate(), f.getZoneId(), f.getCameraId(),
                f.getReason().name().toLowerCase(Locale.ROOT), f.getReasonCode(), f.getReasonText(),
                f.getAmountSek(), f.getIssuedAt(), f.isPaid(), f.getImagePath(), f.getPaidAt(), f.isVoided(), f.getVoidedAt());
    }
}
