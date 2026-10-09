package com.parkview.ruleengine.domain;

/**
 * Outcome of checking a vehicle against the spot it is parked in.
 *
 * @param spot        the spot the vehicle occupies (best overlap)
 * @param iou         intersection over union of vehicle and spot
 * @param containment share of the vehicle that lies inside the spot
 * @param violation   the violation that applies, or null when the parking is valid
 */
public record BoundaryCheckResult(ParkingSpot spot, double iou, double containment, ViolationType violation) {

    public boolean isViolation() {
        return violation != null;
    }
}
