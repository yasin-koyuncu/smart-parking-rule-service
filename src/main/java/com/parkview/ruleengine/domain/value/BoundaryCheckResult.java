package com.parkview.ruleengine.domain.value;

/**
 * Where a vehicle is parked: the spot it occupies and how well it sits inside the lines.
 *
 * @param spot        the spot the vehicle occupies (best overlap)
 * @param iou         intersection over union of vehicle and spot
 * @param containment share of the vehicle that lies inside the spot
 * @param insideLines true when the vehicle counts as parked inside the lines (IoU or containment threshold reached)
 */
public record BoundaryCheckResult(ParkingSpot spot, double iou, double containment, boolean insideLines) {
}
