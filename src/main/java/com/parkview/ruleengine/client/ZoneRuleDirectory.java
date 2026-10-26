package com.parkview.ruleengine.client;

import com.parkview.ruleengine.domain.value.ZoneRule;

import java.util.List;

/**
 * Port: the enabled rules of a zone and of a site, as edited by operators (owned by parking-mapping-service). Today a read of
 * {@code zone_rules}: a row with a zone is a zone rule, a row without one a site rule.
 */
public interface ZoneRuleDirectory {

    /** Enabled rules of the zone in no particular order. */
    List<ZoneRule> enabledRulesOf(String zoneId);

    /** Enabled site rules of the site (its rules without a zone) in no particular order. */
    List<ZoneRule> enabledSiteRulesOf(String siteId);
}
