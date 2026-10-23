package com.parkview.ruleengine.domain.value;

import com.parkview.ruleengine.domain.type.Fuel;
import com.parkview.ruleengine.domain.type.VehicleClass;

/**
 * What the vehicle registry says about a plate (never owner data).
 *
 * @param vehicleClass body type, null when the registry type is not recognised
 * @param fuel         drive, {@link Fuel#UNKNOWN} when not stated
 */
public record VehicleInfo(VehicleClass vehicleClass, Fuel fuel) {
}
