package com.parkview.ruleengine.mapper;

import com.parkview.ruleengine.domain.Dispute;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.util.ReasonTexts;
import com.parkview.ruleengine.dto.response.DisputeResponse;
import com.parkview.ruleengine.service.fine.DisputeDetails;

import java.util.Locale;

public final class DisputeMapper {

    private DisputeMapper() {
    }

    public static DisputeResponse toResponse(DisputeDetails details) {
        Dispute d = details.dispute();
        Fine fine = details.fine();
        return new DisputeResponse(d.getId(), d.getFineId(), d.getPlate(), d.getZoneId(), details.zoneAddress(), d.getSiteId(),
                details.siteAddress(),
                d.getChannel().wire(), d.getMessage(), d.getStatus().wire(), fine.getAmountSek(),
                fine.getReason().name().toLowerCase(Locale.ROOT), reasonText(fine), d.getOpenedAt(), d.getDecidedAt(),
                d.getDecisionNote());
    }

    /** The plain-language text of why the fine was issued. */
    static String reasonText(Fine fine) {
        return ReasonTexts.of(fine.getReason());
    }
}
