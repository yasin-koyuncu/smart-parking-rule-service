package com.parkview.ruleengine.engine;

import com.parkview.ruleengine.domain.value.ParkingSpot;
import com.parkview.ruleengine.domain.type.VehicleClass;
import com.parkview.ruleengine.domain.value.ZoneInfo;

import java.time.Instant;

/**
 * One vehicle observed in one spot.
 *
 * @param zone          master data of the spot's zone
 * @param spot          the spot the vehicle occupies
 * @param insideLines   whether the vehicle sits inside the spot's lines
 * @param plate         normalised plate, null when unknown
 * @param detectedClass body type reported by the detector, null when unknown
 * @param at            observation time
 */
public record Observation(ZoneInfo zone, ParkingSpot spot, boolean insideLines, String plate,
                          VehicleClass detectedClass, Instant at) {
}
