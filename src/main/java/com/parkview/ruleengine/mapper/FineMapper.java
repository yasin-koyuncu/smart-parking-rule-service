package com.parkview.ruleengine.mapper;

import com.parkview.ruleengine.domain.type.DisputeStatus;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.dto.response.FineResponse;
import com.parkview.ruleengine.dto.response.InternalFineResponse;

import java.util.Locale;

public final class FineMapper {

    public static final String CURRENCY = "SEK";

    private FineMapper() {
    }

    /** @param disputed whether a dispute is open: payment-service must not start a payment for the fine */
    public static InternalFineResponse toInternalResponse(Fine f, boolean disputed) {
        return new InternalFineResponse(f.getId(), f.getPlate(), f.getUserId(), f.getZoneId(),
                f.getAmountSek(), CURRENCY, f.isPaid(), f.isVoided(), disputed);
    }

    /**
     * Public view: deliberately without the owner's user id. {@code reason} is the stored lower-case form.
     *
     * @param disputeStatus status of the latest dispute of the fine, null when it never had one; {@code disputed} is true while it is open
     */
    public static FineResponse toResponse(Fine f, DisputeStatus disputeStatus) {
        return new FineResponse(f.getId(), f.getPlate(), f.getZoneId(), f.getCameraId(),
                f.getReason().name().toLowerCase(Locale.ROOT), f.getReasonCode(), f.getReasonText(),
                f.getAmountSek(), f.getIssuedAt(), f.isPaid(), f.getImagePath(), f.getPaidAt(), f.isVoided(), f.getVoidedAt(),
                disputeStatus == DisputeStatus.OPEN, disputeStatus == null ? null : disputeStatus.wire());
    }
}
