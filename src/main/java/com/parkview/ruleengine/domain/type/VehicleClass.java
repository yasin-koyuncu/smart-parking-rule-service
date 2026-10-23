package com.parkview.ruleengine.domain.type;

import java.util.Locale;
import java.util.Optional;

/**
 * Body type of a vehicle, with the code operators use in {@code zone_rules.vehicle_types}.
 * The code {@code ev} is deliberately not here: it is not a body type but a property (electric drive).
 */
public enum VehicleClass {
    CAR("car"),
    MOTORCYCLE("mc"),
    BUS("bus"),
    TRUCK("truck");

    private final String ruleCode;

    VehicleClass(String ruleCode) {
        this.ruleCode = ruleCode;
    }

    public String ruleCode() {
        return ruleCode;
    }

    /** Maps the detector class of {@code vehicle.detected} ({@code car|motorcycle|bus|truck}); anything else is unknown (empty). */
    public static Optional<VehicleClass> fromDetector(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "car" -> Optional.of(CAR);
            case "motorcycle" -> Optional.of(MOTORCYCLE);
            case "bus" -> Optional.of(BUS);
            case "truck" -> Optional.of(TRUCK);
            default -> Optional.empty();
        };
    }

    /** Maps the registry vehicle type of lookup-service (e.g. the Swedish {@code Personbil}); unrecognised values are unknown (empty). */
    public static Optional<VehicleClass> fromRegistry(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.contains("personbil") || v.equals("car")) {
            return Optional.of(CAR);
        }
        if (v.contains("motorcykel") || v.contains("moped") || v.equals("motorcycle")) {
            return Optional.of(MOTORCYCLE);
        }
        if (v.contains("buss") || v.equals("bus")) {
            return Optional.of(BUS);
        }
        if (v.contains("lastbil") || v.equals("truck")) {
            return Optional.of(TRUCK);
        }
        return Optional.empty();
    }
}
