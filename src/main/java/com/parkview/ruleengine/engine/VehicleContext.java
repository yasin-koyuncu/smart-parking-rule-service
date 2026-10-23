package com.parkview.ruleengine.engine;

import com.parkview.ruleengine.client.VehicleDirectory;
import com.parkview.ruleengine.domain.type.Fuel;
import com.parkview.ruleengine.domain.type.VehicleClass;
import com.parkview.ruleengine.domain.value.VehicleInfo;

import java.util.Optional;

/**
 * What is known about the observed vehicle. The detector class comes with the observation; everything else
 * (registry body type, drive) is fetched from the {@link VehicleDirectory} lazily and at most once, and only
 * when a decision asks for it. A missing plate, a disabled lookup or a failed lookup mean "unknown".
 */
final class VehicleContext {

    private final String plate;
    private final VehicleClass detectedClass;
    private final String country;
    private final VehicleDirectory directory;
    private final boolean lookupEnabled;

    private boolean fetched;
    private Optional<VehicleInfo> info = Optional.empty();

    /**
     * @param plate         normalised plate, null when unknown
     * @param detectedClass class reported by the detector, null when unknown
     */
    VehicleContext(String plate, VehicleClass detectedClass, String country, VehicleDirectory directory, boolean lookupEnabled) {
        this.plate = plate;
        this.detectedClass = detectedClass;
        this.country = country;
        this.directory = directory;
        this.lookupEnabled = lookupEnabled;
    }

    /** Body type: the detector class, else the registry's. Empty when neither is known. */
    Optional<VehicleClass> bodyClass() {
        if (detectedClass != null) {
            return Optional.of(detectedClass);
        }
        return registry().map(VehicleInfo::vehicleClass);
    }

    /** {@link Fuel#UNKNOWN} unless the registry knows the vehicle's drive. */
    Fuel fuel() {
        return registry().map(VehicleInfo::fuel).orElse(Fuel.UNKNOWN);
    }

    private Optional<VehicleInfo> registry() {
        if (!fetched) {
            fetched = true;
            if (lookupEnabled && plate != null) {
                info = directory.lookup(plate, country);
            }
        }
        return info;
    }
}
