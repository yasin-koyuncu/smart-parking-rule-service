package com.parkview.ruleengine.client.holiday;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import com.parkview.ruleengine.client.HolidayCalendar;
import static org.assertj.core.api.Assertions.assertThat;

class JollydayHolidayCalendarTest {

    private final HolidayCalendar calendar = new JollydayHolidayCalendar();

    @ParameterizedTest(name = "{1} ({0}) is a Swedish holiday")
    @CsvSource({
            "2026-01-01, Nyarsdagen",
            "2026-01-06, Trettondedag jul",
            "2026-04-03, Langfredagen",
            "2026-04-05, Pascdagen",
            "2026-04-06, Annandag pask",
            "2026-05-01, Forsta maj",
            "2026-05-14, Kristi himmelsfardsdag",
            "2026-05-24, Pingstdagen",
            "2026-06-06, Nationaldagen",
            "2026-06-19, Midsommarafton",
            "2026-06-20, Midsommardagen",
            "2026-10-31, Alla helgons dag",
            "2026-12-25, Juldagen",
            "2026-12-26, Annandag jul",
    })
    void swedishPublicHolidaysOf2026(LocalDate date, String name) {
        assertThat(calendar.isHoliday("SE", date)).as(name).isTrue();
    }

    @ParameterizedTest(name = "{0} is a normal day")
    @CsvSource({"2026-01-02", "2026-03-10", "2026-06-18", "2026-06-22", "2026-10-12", "2026-12-23"})
    void otherDaysAreNotHolidays(LocalDate date) {
        assertThat(calendar.isHoliday("SE", date)).isFalse();
    }

    @Test
    void theCountryCodeIsCaseInsensitive() {
        assertThat(calendar.isHoliday("se", LocalDate.of(2026, 6, 19))).isTrue();
    }

    @Test
    void holidaysDifferPerCountry() {
        // Norway has no Midsummer holiday; Constitution Day is 17 May
        assertThat(calendar.isHoliday("NO", LocalDate.of(2026, 6, 19))).isFalse();
        assertThat(calendar.isHoliday("NO", LocalDate.of(2026, 5, 17))).isTrue();
    }

    @Test
    void anUnsupportedCountryHasNoHolidays() {
        assertThat(calendar.isHoliday("ZZ", LocalDate.of(2026, 1, 1))).isFalse();
    }
}
