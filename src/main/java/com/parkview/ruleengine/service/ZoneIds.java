package com.parkview.ruleengine.service;

/** Zone id helper: one normal form for a zone id that comes from a request. */
public final class ZoneIds {

    private ZoneIds() {
    }

    /** Trimmed; null stays null and blank becomes null (which means "every zone" for a permit). */
    public static String normalize(String zoneId) {
        return zoneId == null || zoneId.isBlank() ? null : zoneId.trim();
    }
}
