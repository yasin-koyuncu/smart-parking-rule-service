package com.parkview.ruleengine.domain;

/** Plain-language wording of why a fine was issued, for the driver and the operator who reviews a dispute. */
public final class ReasonTexts {

    private ReasonTexts() {
    }

    public static String of(ViolationType type) {
        return switch (type) {
            case NO_PARKING -> "The vehicle was parked where parking is not allowed.";
            case OVERSTAY -> "The vehicle stayed longer than the time allowed here.";
            case WRONG_PERMIT -> "The vehicle was parked in a spot that needs a permit, and no valid permit was registered for it.";
            case BOUNDARY_EXCEEDED -> "The vehicle was parked outside the lines of the parking spot.";
            case WRONG_VEHICLE_TYPE -> "The vehicle was parked in a spot reserved for electric vehicles.";
        };
    }
}
