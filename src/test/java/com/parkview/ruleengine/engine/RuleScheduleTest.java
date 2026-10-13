package com.parkview.ruleengine.engine;

import com.parkview.ruleengine.domain.ZoneRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class RuleScheduleTest {

    private static final ZoneId STOCKHOLM = ZoneId.of("Europe/Stockholm");
    private static final Predicate<LocalDate> NO_HOLIDAYS = date -> false;
    private static final Set<LocalDate> HOLIDAYS = Set.of(
            LocalDate.of(2026, 6, 19), LocalDate.of(2026, 12, 25), LocalDate.of(2026, 1, 1));

    private static ZonedDateTime at(String localDateTime) {
        return java.time.LocalDateTime.parse(localDateTime).atZone(STOCKHOLM);
    }

    // 2026-10-12 is a Monday
    @ParameterizedTest(name = "{0} window {1}-{2} on weekdays [1..5]: {3}")
    @CsvSource({
            "2026-10-12T08:00:00, 08:00, 18:00, true",
            "2026-10-12T07:59:59, 08:00, 18:00, false",
            "2026-10-12T17:59:59, 08:00, 18:00, true",
            "2026-10-12T18:00:00, 08:00, 18:00, false",
            "2026-10-16T12:00:00, 08:00, 18:00, true",
            "2026-10-17T12:00:00, 08:00, 18:00, false",
            "2026-10-18T12:00:00, 08:00, 18:00, false",
            "2026-10-12T00:00:00, 00:00, 23:59, true",
            "2026-10-12T23:59:30, 00:00, 23:59, true",
            "2026-10-12T15:00:00, 10:00, 10:00, true",
    })
    void sameDayWindowIsInclusiveAtTheStartAndExclusiveAtTheEnd(String now, String start, String end, boolean expected) {
        ZoneRule rule = Rules.rule().weekdays(1, 2, 3, 4, 5).window(start, end).build();

        assertThat(RuleSchedule.applies(rule, at(now), NO_HOLIDAYS)).isEqualTo(expected);
    }

    // rule: Friday 22:00 - 06:00 (the window belongs to the weekday it starts on); 2026-10-16 is a Friday
    @ParameterizedTest(name = "Friday night window at {0}: {1}")
    @CsvSource({
            "2026-10-16T21:59:59, false",
            "2026-10-16T22:00:00, true",
            "2026-10-16T23:59:59, true",
            "2026-10-17T00:00:00, true",
            "2026-10-17T05:59:59, true",
            "2026-10-17T06:00:00, false",
            "2026-10-17T22:00:00, false",
            "2026-10-16T03:00:00, false",
            "2026-10-18T03:00:00, false",
    })
    void aWindowCrossingMidnightBelongsToTheWeekdayItStartsOn(String now, boolean expected) {
        ZoneRule rule = Rules.rule().weekdays(5).window("22:00", "06:00").build();

        assertThat(RuleSchedule.applies(rule, at(now), NO_HOLIDAYS)).isEqualTo(expected);
    }

    // 2026-06-19 (Friday) is Midsommarafton
    @ParameterizedTest(name = "holiday rule appliesHolidays={0}, date {1}: {2}")
    @CsvSource({
            "false, 2026-06-19T12:00:00, false",
            "true,  2026-06-19T12:00:00, true",
            "false, 2026-06-18T12:00:00, true",
            "true,  2026-06-18T12:00:00, true",
            "false, 2026-06-20T12:00:00, true",
    })
    void holidaysOnlyMatterForRulesThatExcludeThem(boolean appliesHolidays, String now, boolean expected) {
        ZoneRule rule = Rules.rule().holidays(appliesHolidays).build();

        assertThat(RuleSchedule.applies(rule, at(now), HOLIDAYS::contains)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "night window at {0}: {1}")
    @CsvSource({
            "2026-12-25T23:00:00, false",
            "2026-12-26T03:00:00, false",
            "2026-12-25T03:00:00, true",
            "2026-12-26T23:00:00, true",
    })
    void theHolidayStatusOfAWindowCrossingMidnightIsThatOfTheDayItStarts(String now, boolean expected) {
        ZoneRule rule = Rules.rule().holidays(false).window("22:00", "06:00").build();

        assertThat(RuleSchedule.applies(rule, at(now), HOLIDAYS::contains)).isEqualTo(expected);
    }

    @Test
    void theZonesTimezoneDecidesTheWeekdayAndTheHour() {
        // 2026-07-01T22:30Z is Wednesday 22:30 in UTC but Thursday 00:30 in Stockholm (CEST, UTC+2)
        Instant instant = Instant.parse("2026-07-01T22:30:00Z");
        ZoneRule thursdayAfterMidnight = Rules.rule().weekdays(4).window("00:00", "01:00").build();

        assertThat(RuleSchedule.applies(thursdayAfterMidnight, instant.atZone(STOCKHOLM), NO_HOLIDAYS)).isTrue();
        assertThat(RuleSchedule.applies(thursdayAfterMidnight, instant.atZone(ZoneId.of("UTC")), NO_HOLIDAYS)).isFalse();
    }

    @Test
    void springForwardSkipsAnHourOnTheWallClock() {
        // 2026-03-29: at 01:00Z clocks go from 02:00 CET to 03:00 CEST
        ZoneRule night = Rules.rule().weekdays(7).window("02:30", "03:30").build();
        ZoneRule beforeTheJump = Rules.rule().weekdays(7).window("01:00", "02:00").build();

        ZonedDateTime justBefore = Instant.parse("2026-03-29T00:59:59Z").atZone(STOCKHOLM);
        ZonedDateTime justAfter = Instant.parse("2026-03-29T01:00:00Z").atZone(STOCKHOLM);

        assertThat(justBefore.toLocalTime().toString()).isEqualTo("01:59:59");
        assertThat(justAfter.toLocalTime().toString()).isEqualTo("03:00");
        assertThat(RuleSchedule.applies(beforeTheJump, justBefore, NO_HOLIDAYS)).isTrue();
        assertThat(RuleSchedule.applies(beforeTheJump, justAfter, NO_HOLIDAYS)).isFalse();
        assertThat(RuleSchedule.applies(night, justAfter, NO_HOLIDAYS)).isTrue();
    }

    @Test
    void fallBackRepeatsAnHourAndBothOccurrencesMatch() {
        // 2026-10-25: clocks go from 03:00 CEST back to 02:00 CET; 02:30 happens twice
        ZoneRule window = Rules.rule().weekdays(7).window("02:00", "03:00").build();

        ZonedDateTime first = Instant.parse("2026-10-25T00:30:00Z").atZone(STOCKHOLM);
        ZonedDateTime second = Instant.parse("2026-10-25T01:30:00Z").atZone(STOCKHOLM);

        assertThat(first.toLocalTime()).isEqualTo(second.toLocalTime());
        assertThat(RuleSchedule.applies(window, first, NO_HOLIDAYS)).isTrue();
        assertThat(RuleSchedule.applies(window, second, NO_HOLIDAYS)).isTrue();
    }

    @Test
    void aNightWindowSpanningTheClockChangeStillBelongsToItsStartDay() {
        ZoneRule saturdayNight = Rules.rule().weekdays(6).window("22:00", "06:00").build();

        ZonedDateTime sundayMorning = Instant.parse("2026-03-29T04:00:00Z").atZone(STOCKHOLM);

        assertThat(sundayMorning.toLocalTime().toString()).isEqualTo("06:00");
        assertThat(RuleSchedule.applies(saturdayNight, sundayMorning, NO_HOLIDAYS)).isFalse();
        assertThat(RuleSchedule.applies(saturdayNight, sundayMorning.minusHours(1), NO_HOLIDAYS)).isTrue();
    }
}
