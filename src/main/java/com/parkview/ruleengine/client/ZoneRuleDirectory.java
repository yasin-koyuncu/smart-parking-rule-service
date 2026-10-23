package com.parkview.ruleengine.client;

import com.parkview.ruleengine.domain.value.ZoneRule;

import java.util.List;

/** Port: the enabled rules of a zone, as edited by operators (owned by parking-mapping-service). Today a read of {@code zone_rules}. */
public interface ZoneRuleDirectory {

    /** Enabled rules of the zone in no particular order. */
    List<ZoneRule> enabledRulesOf(String zoneId);
}
