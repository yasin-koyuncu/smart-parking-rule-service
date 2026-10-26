package com.parkview.ruleengine.engine;

import com.parkview.ruleengine.domain.value.ParkingSpot;
import com.parkview.ruleengine.domain.type.VehicleClass;
import com.parkview.ruleengine.domain.value.ZoneInfo;

import java.time.Instant;

/**
 * One vehicle observed in one spot. The spot names its site and, when it has one, its zone (ADR 0009).
 *
 * @param zone          master data of the place whose parameters apply: the spot's zone, or its site when it has no zone
 * @param spot          the spot the vehicle occupies
 * @param insideLines   whether the vehicle sits inside the spot's lines
 * @param plate         normalised plate, null when unknown
 * @param detectedClass body type reported by the detector, null when unknown
 * @param at            observation time
 */
public record Observation(ZoneInfo zone, ParkingSpot spot, boolean insideLines, String plate,
                          VehicleClass detectedClass, Instant at) {
}
