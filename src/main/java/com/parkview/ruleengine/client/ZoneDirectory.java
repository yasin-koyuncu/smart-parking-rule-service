package com.parkview.ruleengine.client;

import com.parkview.ruleengine.domain.ZoneInfo;

import java.util.Optional;

/** Port: zone master data owned by parking-mapping-service. Today a read of {@code zones}. */
public interface ZoneDirectory {

    Optional<String> addressOf(String zoneId);

    /** Address, stay limit, country and timezone of the zone (defaults applied for missing values); empty when the zone is unknown. */
    Optional<ZoneInfo> infoOf(String zoneId);
}
