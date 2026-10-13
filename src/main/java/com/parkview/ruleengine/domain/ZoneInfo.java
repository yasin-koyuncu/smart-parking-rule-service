package com.parkview.ruleengine.domain;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;

/**
 * Zone master data the engine needs, with the defaults used when the zone row lacks a value.
 *
 * @param maxMinutes zone-wide stay limit, zero means none
 * @param country    ISO country code of the zone (selects the holiday calendar)
 * @param timezone   zone in which rule windows are interpreted
 */
public record ZoneInfo(String address, int maxMinutes, String country, ZoneId timezone) {

    public static final String DEFAULT_COUNTRY = "SE";
    public static final ZoneId DEFAULT_TIMEZONE = ZoneId.of("Europe/Stockholm");

    /** Builds the info from raw column values; null, blank or invalid values fall back to the defaults. */
    public static ZoneInfo of(String address, Integer maxMinutes, String country, String timezone) {
        return new ZoneInfo(
                address == null || address.isBlank() ? null : address,
                maxMinutes == null ? 0 : Math.max(maxMinutes, 0),
                country == null || country.isBlank() ? DEFAULT_COUNTRY : country.trim().toUpperCase(Locale.ROOT),
                parseZone(timezone));
    }

    /** A zone that is not in the database at all. */
    public static ZoneInfo unknown() {
        return new ZoneInfo(null, 0, DEFAULT_COUNTRY, DEFAULT_TIMEZONE);
    }

    private static ZoneId parseZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return DEFAULT_TIMEZONE;
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (DateTimeException e) {
            return DEFAULT_TIMEZONE;
        }
    }
}
