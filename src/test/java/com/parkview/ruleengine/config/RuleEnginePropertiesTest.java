package com.parkview.ruleengine.config;

import com.parkview.ruleengine.domain.ViolationType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

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
