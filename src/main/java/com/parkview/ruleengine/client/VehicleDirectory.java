package com.parkview.ruleengine.client;

import com.parkview.ruleengine.domain.value.VehicleInfo;

import java.util.Optional;

/** Port: vehicle registry data (body type, drive) for a plate, never owner data. Today an HTTP call to lookup-service. */
public interface VehicleDirectory {

    /** @return the registry data, or empty when the plate is unknown or the registry cannot be reached */
    Optional<VehicleInfo> lookup(String normalizedPlate, String country);
}
