package com.parkview.ruleengine.mapper;

import com.parkview.ruleengine.domain.FineAction;
import com.parkview.ruleengine.dto.response.FineActionResponse;

public final class FineActionMapper {

    private FineActionMapper() {
    }

    public static FineActionResponse toResponse(FineAction a) {
        return new FineActionResponse(a.getId(), a.getAt(), a.getActorId(), a.getAction(), a.getReason(), a.getDetails());
    }
}
