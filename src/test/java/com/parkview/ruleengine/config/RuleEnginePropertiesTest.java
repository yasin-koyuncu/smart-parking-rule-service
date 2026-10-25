package com.parkview.ruleengine.config;

import com.parkview.ruleengine.domain.value.ParkingSpot;
import com.parkview.ruleengine.domain.type.ViolationType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEnginePropertiesTest {

    private static RuleEngineProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("parkview.rule-engine", RuleEngineProperties.class);
    }

    @Test
    void theNewPropertiesHaveTheDocumentedDefaults() {
        RuleEngineProperties defaults = bind(Map.of());

        assertThat(defaults.noParkingGraceMinutes()).isEqualTo(5);
        assertThat(defaults.overstayGraceMinutes()).isEqualTo(5);
        assertThat(defaults.wrongVehicleTypeGraceMinutes()).isEqualTo(10);
        assertThat(defaults.fineAmountWrongVehicleTypeSek()).isEqualByComparingTo("700");
        assertThat(defaults.ruleCacheTtl()).isEqualTo(Duration.ofSeconds(60));
        assertThat(defaults.vehicleLookupEnabled()).isTrue();
        assertThat(defaults.touchInterval()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void theNewPropertiesBindFromTheirKebabCaseNames() {
        RuleEngineProperties props = bind(Map.of(
                "parkview.rule-engine.no-parking-grace-minutes", "1",
                "parkview.rule-engine.overstay-grace-minutes", "2",
                "parkview.rule-engine.wrong-vehicle-type-grace-minutes", "3",
                "parkview.rule-engine.fine-amount-wrong-vehicle-type-sek", "1234",
                "parkview.rule-engine.rule-cache-ttl-seconds", "5",
                "parkview.rule-engine.vehicle-lookup-enabled", "false",
                "parkview.rule-engine.expire-sweep-ms", "300"));

        assertThat(props.noParkingGraceMinutes()).isEqualTo(1);
        assertThat(props.overstayGraceMinutes()).isEqualTo(2);
        assertThat(props.wrongVehicleTypeGraceMinutes()).isEqualTo(3);
        assertThat(props.fineAmountFor(ViolationType.WRONG_VEHICLE_TYPE)).isEqualByComparingTo(new BigDecimal("1234"));
        assertThat(props.ruleCacheTtl()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.vehicleLookupEnabled()).isFalse();
        assertThat(props.expireSweepMs()).isEqualTo(300);
    }

    @Test
    void everyViolationTypeHasAFineAmount() {
        RuleEngineProperties defaults = bind(Map.of());

        for (ViolationType type : ViolationType.values()) {
            assertThat(defaults.fineAmountFor(type)).isPositive();
        }
    }

    @Test
    void graceComesFromTheSpotWhereItDefinesOneAndOtherwiseFromTheConfiguredDefaults() {
        RuleEngineProperties defaults = bind(Map.of());
        ParkingSpot own = spot(4, 7);
        ParkingSpot none = spot(null, null);

        assertThat(defaults.graceMinutesFor(own, ViolationType.BOUNDARY_EXCEEDED)).isEqualTo(4);
        assertThat(defaults.graceMinutesFor(own, ViolationType.WRONG_PERMIT)).isEqualTo(7);
        assertThat(defaults.graceMinutesFor(none, ViolationType.BOUNDARY_EXCEEDED)).isEqualTo(10);
        assertThat(defaults.graceMinutesFor(none, ViolationType.WRONG_PERMIT)).isEqualTo(30);
        // the other types ignore the spot
        assertThat(defaults.graceMinutesFor(own, ViolationType.NO_PARKING)).isEqualTo(5);
        assertThat(defaults.graceMinutesFor(own, ViolationType.OVERSTAY)).isEqualTo(5);
        assertThat(defaults.graceMinutesFor(own, ViolationType.WRONG_VEHICLE_TYPE)).isEqualTo(10);
    }

    @Test
    void aSpotGraceOfZeroIsZeroAndOnlyAMissingOrNegativeOneFallsBackToTheDefault() {
        RuleEngineProperties defaults = bind(Map.of());

        assertThat(defaults.graceMinutesFor(spot(0, 0), ViolationType.BOUNDARY_EXCEEDED)).isZero();
        assertThat(defaults.graceMinutesFor(spot(0, 0), ViolationType.WRONG_PERMIT)).isZero();
        assertThat(defaults.graceMinutesFor(spot(null, 0), ViolationType.BOUNDARY_EXCEEDED)).isEqualTo(10);
        assertThat(defaults.graceMinutesFor(spot(0, null), ViolationType.WRONG_PERMIT)).isEqualTo(30);
        assertThat(defaults.graceMinutesFor(spot(-1, -1), ViolationType.BOUNDARY_EXCEEDED)).isEqualTo(10);
    }

    @Test
    void theFreshnessObservationAgeCacheAndRefineDefaultsAreTheSafeOnes() {
        RuleEngineProperties defaults = bind(Map.of());
        RuleEngineProperties custom = bind(Map.of(
                "parkview.rule-engine.fine-freshness-seconds", "30",
                "parkview.rule-engine.observation-max-age-seconds", "60",
                "parkview.rule-engine.spot-cache-negative-ttl-seconds", "3",
                "parkview.rule-engine.refine-block-hours", "0"));

        assertThat(defaults.fineFreshness()).isEqualTo(Duration.ofSeconds(120));
        assertThat(defaults.observationMaxAge()).isEqualTo(Duration.ofMinutes(10));
        assertThat(defaults.spotCacheNegativeTtl()).isEqualTo(Duration.ofSeconds(15));
        assertThat(defaults.refineBlock()).isEqualTo(Duration.ofHours(6));
        assertThat(custom.fineFreshness()).isEqualTo(Duration.ofSeconds(30));
        assertThat(custom.observationMaxAge()).isEqualTo(Duration.ofSeconds(60));
        assertThat(custom.spotCacheNegativeTtl()).isEqualTo(Duration.ofSeconds(3));
        assertThat(custom.refineBlockHours()).isZero();
    }

    private static ParkingSpot spot(Integer boundaryGrace, Integer permitGrace) {
        return new ParkingSpot(UUID.randomUUID(), "z1", "A1", "paid", null, null, boundaryGrace, permitGrace, 0);
    }

    @Test
    void lookupPropertiesDefaultToTheLocalLookupService() {
        LookupProperties lookup = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("parkview.lookup", LookupProperties.class);

        assertThat(lookup.baseUrl()).isEqualTo("http://localhost:8089");
        assertThat(lookup.connectTimeoutMs()).isEqualTo(500);
        assertThat(lookup.readTimeoutMs()).isEqualTo(1500);
        assertThat(lookup.cacheTtl()).isEqualTo(Duration.ofMinutes(60));
        assertThat(lookup.negativeCacheTtl()).isEqualTo(Duration.ofSeconds(60));
    }
}
