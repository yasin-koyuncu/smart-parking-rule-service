package com.parkview.ruleengine.domain.value;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One operator-defined rule of a zone, or of a site ({@code zone_rules}, edited in the operator UI). A site rule has no zone
 * and applies in the whole site, inside its zones too; at equal priority a zone rule is evaluated before a site rule.
 *
 * @param zoneId          the zone of a zone rule, null for a site rule
 * @param priority        lower number is evaluated first
 * @param vehicleTypes    codes {@code car|mc|truck|bus|ev}; empty means all vehicles
 * @param permitRequired  {@code resident|disabled|ev|loading} or null
 * @param weekdays        ISO weekdays 1 (Monday) to 7 (Sunday) on which a window starts
 * @param start           window start (inclusive)
 * @param end             window end (exclusive); before {@code start} the window crosses midnight
 * @param appliesHolidays whether the rule also applies on public holidays
 * @param maxMinutes      stay limit overriding the zone default, null when the rule defines none
 * @param noParking       parking is forbidden while the rule applies
 */
public record ZoneRule(
        UUID id,
        String zoneId,
        String label,
        int priority,
        List<String> vehicleTypes,
        String permitRequired,
        Set<Integer> weekdays,
        LocalTime start,
        LocalTime end,
        boolean appliesHolidays,
        Integer maxMinutes,
        boolean noParking
) {

    /** Stay limit in minutes, zero when the rule defines none (a limit of zero or less is not a limit). */
    public int definedMaxMinutes() {
        return maxMinutes == null ? 0 : Math.max(maxMinutes, 0);
    }

    public boolean definesPermit() {
        return permitRequired != null && !permitRequired.isBlank();
    }

    public boolean definesMaxMinutes() {
        return definedMaxMinutes() > 0;
    }

    /** A rule of the site (no zone), as opposed to a rule of a zone. */
    public boolean isSiteRule() {
        return zoneId == null;
    }
}
