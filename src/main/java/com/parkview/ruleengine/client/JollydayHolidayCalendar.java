package com.parkview.ruleengine.client;

import de.focus_shift.jollyday.core.HolidayManager;
import de.focus_shift.jollyday.core.HolidayType;
import de.focus_shift.jollyday.core.ManagerParameters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public holidays from the Jollyday library. One manager per country is created on first use and kept
 * (Jollyday caches the computed holidays per year). A country Jollyday does not support has no holidays.
 */
@Slf4j
@Component
class JollydayHolidayCalendar implements HolidayCalendar {

    private final Map<String, Optional<HolidayManager>> managers = new ConcurrentHashMap<>();

    @Override
    public boolean isHoliday(String country, LocalDate date) {
        String code = country.toUpperCase(Locale.ROOT);
        return managers.computeIfAbsent(code, this::createManager)
                .map(manager -> manager.isHoliday(date, HolidayType.PUBLIC_HOLIDAY))
                .orElse(false);
    }

    private Optional<HolidayManager> createManager(String country) {
        try {
            de.focus_shift.jollyday.core.HolidayCalendar calendar = Arrays.stream(de.focus_shift.jollyday.core.HolidayCalendar.values())
                    .filter(c -> c.getId().equalsIgnoreCase(country))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unsupported country"));
            return Optional.of(HolidayManager.getInstance(ManagerParameters.create(calendar)));
        } catch (RuntimeException e) {
            log.warn("No holiday calendar for country {}: rules with applies_holidays=false are treated as on a normal day ({})",
                    country, e.getMessage());
            return Optional.empty();
        }
    }
}
