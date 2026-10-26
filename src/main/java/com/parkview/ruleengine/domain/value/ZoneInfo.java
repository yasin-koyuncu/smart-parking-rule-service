package com.parkview.ruleengine.domain.value;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;

/**
 * Master data of the place whose parameters apply at a spot, with the defaults used when the row lacks a value: the zone's
 * when the spot has a zone, otherwise the site's (ADR 0009: a site carries its own stay limit and "parking allowed" like a
 * zone, and a zone overrides them inside it).
 *
 * @param address       street address of the zone or site, null when it has none
 * @param maxMinutes    stay limit of the zone or site, zero means none
 * @param country       ISO country code (selects the holiday calendar)
 * @param timezone      zone in which rule windows are interpreted
 * @param allowsParking false when parking is not allowed at all in the zone or site
 * @param siteLevel     true when these are the parameters of a site (the spot has no zone), false for a zone's
 */
public record ZoneInfo(String address, int maxMinutes, String country, ZoneId timezone, boolean allowsParking, boolean siteLevel) {

    public static final String DEFAULT_COUNTRY = "SE";
    public static final ZoneId DEFAULT_TIMEZONE = ZoneId.of("Europe/Stockholm");

    /** The master data of a zone in which parking is allowed. */
    public ZoneInfo(String address, int maxMinutes, String country, ZoneId timezone) {
        this(address, maxMinutes, country, timezone, true, false);
    }

    /** Builds the info of a zone from raw column values; null, blank or invalid values fall back to the defaults. */
    public static ZoneInfo of(String address, Integer maxMinutes, String country, String timezone) {
        return of(address, maxMinutes, country, timezone, true);
    }

    /** Same, with whether parking is allowed in the zone. */
    public static ZoneInfo of(String address, Integer maxMinutes, String country, String timezone, boolean allowsParking) {
        return build(address, maxMinutes, country, timezone, allowsParking, false);
    }

    /** Builds the info of a site from raw column values, with the same defaults. */
    public static ZoneInfo ofSite(String address, Integer maxMinutes, String country, String timezone, boolean allowsParking) {
        return build(address, maxMinutes, country, timezone, allowsParking, true);
    }

    /** A zone that is not in the database at all. */
    public static ZoneInfo unknown() {
        return new ZoneInfo(null, 0, DEFAULT_COUNTRY, DEFAULT_TIMEZONE);
    }

    /** A site that is not in the database at all: no limit, parking allowed, the defaults. */
    public static ZoneInfo unknownSite() {
        return new ZoneInfo(null, 0, DEFAULT_COUNTRY, DEFAULT_TIMEZONE, true, true);
    }

    private static ZoneInfo build(String address, Integer maxMinutes, String country, String timezone, boolean allowsParking,
                                  boolean siteLevel) {
        return new ZoneInfo(
                address == null || address.isBlank() ? null : address,
                maxMinutes == null ? 0 : Math.max(maxMinutes, 0),
                country == null || country.isBlank() ? DEFAULT_COUNTRY : country.trim().toUpperCase(Locale.ROOT),
                parseZone(timezone),
                allowsParking,
                siteLevel);
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
