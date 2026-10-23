package com.parkview.ruleengine.engine;

import com.parkview.ruleengine.domain.value.ParkingSpot;
import com.parkview.ruleengine.domain.type.ViolationType;
import com.parkview.ruleengine.domain.value.ZoneRule;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The rule that decided a violation, in plain language. The text is built when the violation is opened and later frozen
 * into the evidence of the fine, so it must not refer to anything that operators can still edit (no rule ids; the label
 * only as quoted text).
 */
final class RuleSummaries {

    private static final LocalTime END_OF_DAY_MARKER = LocalTime.of(23, 59);

    private RuleSummaries() {
    }

    /**
     * @param limitMinutes the effective stay limit that was applied (only used for {@code OVERSTAY})
     */
    static String of(ViolationType type, Observation observation, RuleOutcome outcome, int limitMinutes) {
        ParkingSpot spot = observation.spot();
        return switch (type) {
            case NO_PARKING -> noParking(outcome);
            case WRONG_VEHICLE_TYPE ->
                    "The spot is reserved for electric vehicles and the vehicle registry shows that the vehicle is not electric.";
            case WRONG_PERMIT -> wrongPermit(spot, outcome);
            case OVERSTAY -> overstay(spot, outcome, limitMinutes);
            case BOUNDARY_EXCEEDED -> "The vehicle was not inside the lines of the parking spot"
                    + (spot.spotNumber() == null ? "." : " (spot " + spot.spotNumber() + ").");
        };
    }

    private static String noParking(RuleOutcome outcome) {
        ZoneRule rule = outcome.noParkingRule();
        if (rule != null) {
            return "Parking is not allowed while this applies: " + describe(rule) + ".";
        }
        return "Parking is not allowed in this spot (spot type: no parking).";
    }

    private static String wrongPermit(ParkingSpot spot, RuleOutcome outcome) {
        List<String> sources = new ArrayList<>();
        if (spot.requiredPermitType() != null) {
            sources.add("the spot requires a " + spot.requiredPermitType().trim() + " permit");
        }
        ZoneRule rule = outcome.permitRule();
        if (rule != null) {
            sources.add(describe(rule) + " requires a " + outcome.permitRequired() + " permit");
        }
        return "No valid permit was registered for the plate: " + String.join("; ", sources) + ".";
    }

    private static String overstay(ParkingSpot spot, RuleOutcome outcome, int limitMinutes) {
        String source;
        if (outcome.maxMinutes() > 0 && outcome.maxMinutesRule() != null) {
            source = describe(outcome.maxMinutesRule());
        } else if (spot.ownMaxMinutes() > 0) {
            source = "the limit of this spot";
        } else {
            source = "the limit of the zone";
        }
        return "The stay limit is " + limitMinutes + " minutes (" + source + ") and the parking session had lasted longer.";
    }

    /** {@code zone rule "Weekday limit" (Mon-Fri 08:00-18:00, public holidays excluded, vehicles: car, truck)}. */
    static String describe(ZoneRule rule) {
        StringBuilder text = new StringBuilder("zone rule");
        if (rule.label() != null && !rule.label().isBlank()) {
            text.append(" \"").append(rule.label().trim()).append('"');
        }
        text.append(" (").append(days(rule.weekdays())).append(' ').append(window(rule.start(), rule.end()));
        if (!rule.appliesHolidays()) {
            text.append(", public holidays excluded");
        }
        if (!rule.vehicleTypes().isEmpty()) {
            text.append(", vehicles: ").append(String.join(", ", rule.vehicleTypes()));
        }
        return text.append(')').toString();
    }

    private static String window(LocalTime start, LocalTime end) {
        if (start.equals(end)) {
            return "all day";
        }
        return start + "-" + (end.equals(END_OF_DAY_MARKER) ? "midnight" : end.toString());
    }

    /** Consecutive days are written as a range ({@code Mon-Fri}), all seven as {@code every day}. */
    private static String days(Set<Integer> weekdays) {
        TreeSet<Integer> sorted = new TreeSet<>(weekdays);
        if (sorted.size() == 7) {
            return "every day";
        }
        List<String> parts = new ArrayList<>();
        Integer runStart = null;
        Integer previous = null;
        for (Integer day : sorted) {
            if (previous == null || day != previous + 1) {
                addRun(parts, runStart, previous);
                runStart = day;
            }
            previous = day;
        }
        addRun(parts, runStart, previous);
        return parts.isEmpty() ? "no day" : String.join(", ", parts);
    }

    private static void addRun(List<String> parts, Integer from, Integer to) {
        if (from == null) {
            return;
        }
        String first = name(from);
        parts.add(from.equals(to) ? first : first + "-" + name(to));
    }

    private static String name(int isoDay) {
        return DayOfWeek.of(isoDay).getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }
}
