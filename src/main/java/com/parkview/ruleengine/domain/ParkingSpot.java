package com.parkview.ruleengine.domain;

import com.parkview.ruleengine.geometry.Polygon;

import java.util.UUID;

/**
 * A polygon spot as read from {@code parking_spots} (owned by parking-mapping-service).
 *
 * @param type             lower-case spot type, e.g. {@code permit}, {@code no_parking}, {@code paid}
 * @param permitType       permit required in a {@code permit} spot, otherwise null
 * @param boundaryGraceMin grace before a boundary fine; zero or less means "use the default"
 * @param permitGraceMin   grace before a wrong-permit fine; zero or less means "use the default"
 */
public record ParkingSpot(
        UUID id,
        String zoneId,
        String spotNumber,
        String type,
        String permitType,
        Polygon polygon,
        int boundaryGraceMin,
        int permitGraceMin
) {

    public static final String TYPE_PERMIT = "permit";
    public static final String TYPE_NO_PARKING = "no_parking";

    public boolean requiresPermit() {
        return TYPE_PERMIT.equals(type) && permitType != null && !permitType.isBlank();
    }

    public boolean isNoParking() {
        return TYPE_NO_PARKING.equals(type);
    }
}
