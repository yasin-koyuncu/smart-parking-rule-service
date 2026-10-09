package com.parkview.ruleengine.client;

import java.util.Optional;

/** Port: zone master data owned by parking-mapping-service. Today a read of {@code zones}. */
public interface ZoneDirectory {

    Optional<String> addressOf(String zoneId);
}
