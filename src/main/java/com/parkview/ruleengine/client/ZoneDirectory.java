package com.parkview.ruleengine.client;

import com.parkview.ruleengine.domain.value.ZoneInfo;

import java.util.Optional;

/** Port: zone master data owned by parking-mapping-service. Today a read of {@code zones}. */
public interface ZoneDirectory {

    Optional<String> addressOf(String zoneId);

    /** Address, stay limit, parking allowed, country and timezone of the zone (defaults applied for missing values); empty when the zone is unknown. */
    Optional<ZoneInfo> infoOf(String zoneId);

    /** The site the zone belongs to (ADR 0009); empty when the zone is unknown. */
    Optional<String> siteOf(String zoneId);
}
