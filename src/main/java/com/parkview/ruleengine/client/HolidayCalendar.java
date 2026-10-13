package com.parkview.ruleengine.client;

import java.time.LocalDate;

/** Port: public holidays per country. */
public interface HolidayCalendar {

    /** True when {@code date} is a public holiday in the country (ISO code, e.g. {@code SE}); false for unsupported countries. */
    boolean isHoliday(String country, LocalDate date);
}
