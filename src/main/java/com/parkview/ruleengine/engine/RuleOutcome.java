package com.parkview.ruleengine.engine;

import com.parkview.ruleengine.domain.ZoneRule;

import java.util.List;

/**
 * What the zone rules decide for one observation, per outcome category.
 *
 * @param noParking      an applicable rule forbids parking
 * @param permitRequired permit type demanded by the deciding rule, null when no rule demands one
 * @param maxMinutes     stay limit of the deciding rule, zero when no rule defines one
 * @param decidingRules  the rules that decided at least one category, in evaluation order (for the explanation of a fine)
 */
record RuleOutcome(boolean noParking, String permitRequired, int maxMinutes, List<ZoneRule> decidingRules) {

    static final RuleOutcome NONE = new RuleOutcome(false, null, 0, List.of());

    /** The rule that forbids parking, null when none does. */
    ZoneRule noParkingRule() {
        return decidingRules.stream().filter(ZoneRule::noParking).findFirst().orElse(null);
    }

    /** The rule that demands the permit, null when none does. */
    ZoneRule permitRule() {
        return decidingRules.stream().filter(ZoneRule::definesPermit).findFirst().orElse(null);
    }

    /** The rule that sets the stay limit, null when none does. */
    ZoneRule maxMinutesRule() {
        return decidingRules.stream().filter(ZoneRule::definesMaxMinutes).findFirst().orElse(null);
    }
}
