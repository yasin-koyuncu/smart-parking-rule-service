package com.parkview.ruleengine.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.ZoneId;
import java.util.Optional;

import com.parkview.ruleengine.domain.type.Fuel;
import com.parkview.ruleengine.domain.type.VehicleClass;
import com.parkview.ruleengine.domain.value.ZoneInfo;
import static org.assertj.core.api.Assertions.assertThat;

class VehicleMappingTest {

    @ParameterizedTest
    @CsvSource({"car, car", "motorcycle, mc", "bus, bus", "truck, truck", "CAR, car", " Truck , truck"})
    void detectorClassesMapToTheRuleCodes(String detector, String code) {
        assertThat(VehicleClass.fromDetector(detector)).map(VehicleClass::ruleCode).contains(code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "hovercraft", "mc", "ev", "van"})
    @NullSource
    void unknownDetectorClassesAreUnknown(String detector) {
        assertThat(VehicleClass.fromDetector(detector)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"Personbil, car", "Motorcykel, mc", "Moped klass I, mc", "Buss, bus", "Lastbil, truck", "Lätt lastbil, truck", "car, car", "truck, truck"})
    void registryVehicleTypesMapToTheRuleCodes(String registryType, String code) {
        assertThat(VehicleClass.fromRegistry(registryType)).map(VehicleClass::ruleCode).contains(code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Släpvagn", "Traktor"})
    @NullSource
    void unrecognisedRegistryTypesAreUnknown(String registryType) {
        assertThat(VehicleClass.fromRegistry(registryType)).isEqualTo(Optional.empty());
    }

    @ParameterizedTest
    @CsvSource({"El, ELECTRIC", "el, ELECTRIC", "Electric, ELECTRIC", " ELECTRIC , ELECTRIC", "Bensin, OTHER", "Diesel, OTHER",
            "El/Bensin, OTHER", "Hybrid, OTHER", "Etanol, OTHER"})
    void onlyPureElectricIsElectric(String fuel, Fuel expected) {
        assertThat(Fuel.fromRegistry(fuel)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @NullSource
    void aMissingFuelIsUnknown(String fuel) {
        assertThat(Fuel.fromRegistry(fuel)).isEqualTo(Fuel.UNKNOWN);
    }

    @Test
    void zoneInfoAppliesDefaultsForMissingOrInvalidValues() {
        ZoneInfo defaults = ZoneInfo.of(null, null, null, null);
        ZoneInfo invalid = ZoneInfo.of("  ", -5, " ", "Mars/Olympus");
        ZoneInfo real = ZoneInfo.of("Storgatan 1", 120, "no", "Europe/Oslo");

        assertThat(defaults).isEqualTo(new ZoneInfo(null, 0, "SE", ZoneId.of("Europe/Stockholm")));
        assertThat(invalid).isEqualTo(new ZoneInfo(null, 0, "SE", ZoneId.of("Europe/Stockholm")));
        assertThat(real).isEqualTo(new ZoneInfo("Storgatan 1", 120, "NO", ZoneId.of("Europe/Oslo")));
    }
}
