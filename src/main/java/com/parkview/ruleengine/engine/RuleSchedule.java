package com.parkview.ruleengine.engine;

import com.parkview.ruleengine.domain.value.ZoneRule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.function.Predicate;

/**
 * When does a rule's time window apply? Pure logic on the wall-clock time of the zone.
 *
 * <ul>
 *   <li>The window is {@code [start, end)}. An end of {@code 23:59} (the UI default) means "until midnight".
 *       {@code start == end} means the whole day.</li>
 *   <li>{@code end < start} crosses midnight (e.g. 22:00-06:00). The window belongs to the day it
 *       <em>starts</em> on: at Saturday 03:00 the weekday (and holiday status) checked is Friday's.</li>
 *   <li>A rule with {@code applies_holidays = false} does not apply when the date of the window start is a public
 *       holiday; with {@code true} holidays make no difference.</li>
 * </ul>
 */
final class RuleSchedule {

    private static final LocalTime END_OF_DAY_MARKER = LocalTime.of(23, 59);

    private RuleSchedule() {
    }

    /**
     * @param now       the instant to test, in the zone's timezone (so DST is handled by the wall clock)
     * @param isHoliday public-holiday test for a date in the zone's country
     */
    static boolean applies(ZoneRule rule, ZonedDateTime now, Predicate<LocalDate> isHoliday) {
        LocalDate windowStart = windowStartDate(rule, now);
        if (windowStart == null) {
            return false;
        }
        if (!rule.weekdays().contains(windowStart.getDayOfWeek().getValue())) {
            return false;
        }
        return rule.appliesHolidays() || !isHoliday.test(windowStart);
    }

    /** The date on which the window containing {@code now} started, or null when {@code now} is outside the window. */
    private static LocalDate windowStartDate(ZoneRule rule, ZonedDateTime now) {
        LocalTime time = now.toLocalTime();
        LocalDate today = now.toLocalDate();
        LocalTime start = rule.start();
        LocalTime end = rule.end();

        if (start.equals(end)) {
            return today;
        }
        if (start.isBefore(end)) {
            boolean untilMidnight = end.equals(END_OF_DAY_MARKER);
            boolean inside = !time.isBefore(start) && (untilMidnight || time.isBefore(end));
            return inside ? today : null;
        }
        if (!time.isBefore(start)) {
            return today;
        }
        return time.isBefore(end) ? today.minusDays(1) : null;
    }
}
