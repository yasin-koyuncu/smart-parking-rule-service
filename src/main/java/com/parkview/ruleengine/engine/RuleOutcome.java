package com.parkview.ruleengine.engine;

/**
 * What the zone rules decide for one observation, per outcome category.
 *
 * @param noParking      an applicable rule forbids parking
 * @param permitRequired permit type demanded by the deciding rule, null when no rule demands one
 * @param maxMinutes     stay limit of the deciding rule, zero when no rule defines one
 */
record RuleOutcome(boolean noParking, String permitRequired, int maxMinutes) {

    static final RuleOutcome NONE = new RuleOutcome(false, null, 0);
}
