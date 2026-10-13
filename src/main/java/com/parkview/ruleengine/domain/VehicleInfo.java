package com.parkview.ruleengine.domain;

/**
 * What the vehicle registry says about a plate (never owner data).
 *
 * @param vehicleClass body type, null when the registry type is not recognised
 * @param fuel         drive, {@link Fuel#UNKNOWN} when not stated
 */
public record VehicleInfo(VehicleClass vehicleClass, Fuel fuel) {
}
