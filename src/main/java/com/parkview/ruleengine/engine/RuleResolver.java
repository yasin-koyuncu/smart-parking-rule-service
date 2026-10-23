package com.parkview.ruleengine.engine;

import com.parkview.ruleengine.client.HolidayCalendar;
import com.parkview.ruleengine.domain.type.Fuel;
import com.parkview.ruleengine.domain.value.ZoneInfo;
import com.parkview.ruleengine.domain.value.ZoneRule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Selects the rules that apply to an observation and derives the outcome.
 *
 * <p>Rules are tried by priority ascending (lower number first), then by id. A rule applies when it is enabled
 * (the directory only returns enabled ones), its time window matches (see {@link RuleSchedule}) and its vehicle
 * filter matches. <strong>First match wins per outcome category</strong>: the first applicable rule that sets
 * {@code no_parking}, the first that sets {@code permit_required} and the first that sets {@code max_minutes} decide
 * that category; later rules cannot override it. A rule that only defines categories already decided is skipped
 * without evaluating its window or vehicle filter (so no registry lookup is made for it).
 *
 * <p>A rule with a vehicle filter applies only when the vehicle's code is known and listed ({@code car, mc, bus,
 * truck}), or when {@code ev} is listed and the vehicle is known to be electric. An unknown vehicle never matches a filter.
 */
@Component
@RequiredArgsConstructor
class RuleResolver {

    private static final String EV_CODE = "ev";
    private static final Comparator<ZoneRule> EVALUATION_ORDER =
            Comparator.comparingInt(ZoneRule::priority).thenComparing(ZoneRule::id);

    private final HolidayCalendar holidays;
    private final EngineMetrics metrics;

    RuleOutcome resolve(List<ZoneRule> rules, ZoneInfo zone, Instant at, VehicleContext vehicle) {
        if (rules.isEmpty()) {
            return RuleOutcome.NONE;
        }
        var now = at.atZone(zone.timezone());
        boolean noParkingDecided = false;
        boolean permitDecided = false;
        boolean maxMinutesDecided = false;
        boolean noParking = false;
        String permitRequired = null;
        int maxMinutes = 0;
        List<ZoneRule> deciding = new ArrayList<>();

        for (ZoneRule rule : rules.stream().sorted(EVALUATION_ORDER).toList()) {
            boolean decidesNoParking = !noParkingDecided && rule.noParking();
            boolean decidesPermit = !permitDecided && rule.definesPermit();
            boolean decidesMaxMinutes = !maxMinutesDecided && rule.definesMaxMinutes();
            if (!(decidesNoParking || decidesPermit || decidesMaxMinutes)) {
                continue;
            }
            if (!RuleSchedule.applies(rule, now, date -> holidays.isHoliday(zone.country(), date))
                    || !vehicleMatches(rule, vehicle)) {
                continue;
            }
            deciding.add(rule);
            if (decidesNoParking) {
                noParkingDecided = true;
                noParking = true;
                metrics.ruleMatched("no_parking");
            }
            if (decidesPermit) {
                permitDecided = true;
                permitRequired = rule.permitRequired().trim();
                metrics.ruleMatched("permit");
            }
            if (decidesMaxMinutes) {
                maxMinutesDecided = true;
                maxMinutes = rule.definedMaxMinutes();
                metrics.ruleMatched("max_minutes");
            }
            if (noParkingDecided && permitDecided && maxMinutesDecided) {
                break;
            }
        }
        return new RuleOutcome(noParking, permitRequired, maxMinutes, List.copyOf(deciding));
    }

    private static boolean vehicleMatches(ZoneRule rule, VehicleContext vehicle) {
        List<String> types = rule.vehicleTypes();
        if (types.isEmpty()) {
            return true;
        }
        boolean listsBodyCode = types.stream().anyMatch(code -> !EV_CODE.equals(code));
        if (listsBodyCode && vehicle.bodyClass().map(c -> types.contains(c.ruleCode())).orElse(false)) {
            return true;
        }
        return types.contains(EV_CODE) && vehicle.fuel() == Fuel.ELECTRIC;
    }
}
