package com.parkview.ruleengine.domain.value;

import com.parkview.ruleengine.geometry.Polygon;

import java.util.UUID;

/**
 * A polygon spot as read from {@code parking_spots} (owned by parking-mapping-service).
 *
 * @param type             lower-case spot type, e.g. {@code permit}, {@code no_parking}, {@code paid}
 * @param permitType       permit required in a {@code permit} spot, otherwise null
 * @param boundaryGraceMin grace before a boundary fine in minutes; null means "use the default", 0 means no grace
 * @param permitGraceMin   grace before a wrong-permit fine in minutes; null means "use the default", 0 means no grace
 * @param maxMinutes       maximum stay in a {@code timed} or {@code loading} spot; zero or less means none
 */
public record ParkingSpot(
        UUID id,
        String zoneId,
        String spotNumber,
        String type,
        String permitType,
        Polygon polygon,
        Integer boundaryGraceMin,
        Integer permitGraceMin,
        int maxMinutes
) {

    public static final String TYPE_PERMIT = "permit";
    public static final String TYPE_NO_PARKING = "no_parking";
    public static final String TYPE_EV = "ev";
    public static final String TYPE_DISABLED = "disabled";
    public static final String TYPE_TIMED = "timed";
    public static final String TYPE_LOADING = "loading";

    public boolean isNoParking() {
        return TYPE_NO_PARKING.equals(type);
    }

    public boolean isEv() {
        return TYPE_EV.equals(type);
    }

    /** The permit type the spot itself demands, or null: {@code disabled} spots need a disabled permit, {@code permit} spots their {@code permit_type}. */
    public String requiredPermitType() {
        if (TYPE_DISABLED.equals(type)) {
            return TYPE_DISABLED;
        }
        if (TYPE_PERMIT.equals(type) && permitType != null && !permitType.isBlank()) {
            return permitType;
        }
        return null;
    }

    /** The spot's own stay limit in minutes (only {@code timed} and {@code loading} spots have one), or zero. */
    public int ownMaxMinutes() {
        return (TYPE_TIMED.equals(type) || TYPE_LOADING.equals(type)) && maxMinutes > 0 ? maxMinutes : 0;
    }
}
