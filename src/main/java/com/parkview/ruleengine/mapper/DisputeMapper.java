package com.parkview.ruleengine.mapper;

import com.parkview.ruleengine.domain.Dispute;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.ReasonTexts;
import com.parkview.ruleengine.dto.response.DisputeResponse;
import com.parkview.ruleengine.service.DisputeDetails;

import java.util.Locale;

public final class DisputeMapper {

    private DisputeMapper() {
    }

    public static DisputeResponse toResponse(DisputeDetails details) {
        Dispute d = details.dispute();
        Fine fine = details.fine();
        return new DisputeResponse(d.getId(), d.getFineId(), d.getPlate(), d.getZoneId(), details.zoneAddress(),
                d.getChannel().wire(), d.getMessage(), d.getStatus().wire(), fine.getAmountSek(),
                fine.getReason().name().toLowerCase(Locale.ROOT), reasonText(fine), d.getOpenedAt(), d.getDecidedAt(),
                d.getDecisionNote());
    }

    /** The text stored with the fine when another producer wrote one, else the plain-language text of the violation type. */
    static String reasonText(Fine fine) {
        String stored = fine.getReasonText();
        return stored != null && !stored.isBlank() ? stored : ReasonTexts.of(fine.getReason());
    }
}
