package com.parkview.ruleengine.engine;

import com.parkview.ruleengine.client.ActiveSessionDirectory;
import com.parkview.ruleengine.client.HolidayCalendar;
import com.parkview.ruleengine.client.VehicleDirectory;
import com.parkview.ruleengine.config.RuleEngineProperties;
import com.parkview.ruleengine.domain.Fuel;
import com.parkview.ruleengine.domain.ParkingSpot;
import com.parkview.ruleengine.domain.VehicleClass;
import com.parkview.ruleengine.domain.VehicleInfo;
import com.parkview.ruleengine.domain.ViolationType;
import com.parkview.ruleengine.domain.ZoneInfo;
import com.parkview.ruleengine.domain.ZoneRule;
import com.parkview.ruleengine.geometry.Point;
import com.parkview.ruleengine.geometry.Polygon;
import com.parkview.ruleengine.service.PermitService;
import com.parkview.ruleengine.service.RuleCacheService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ViolationEvaluatorTest {

    // Monday 2026-10-12 10:00 Stockholm
    private static final Instant NOW = Instant.parse("2026-10-12T08:00:00Z");
    private static final String ZONE_ID = "zone-1";
    private static final String PLATE = "ABC123";

    private final RuleCacheService ruleCache = mock(RuleCacheService.class);
    private final PermitService permits = mock(PermitService.class);
    private final ActiveSessionDirectory sessions = mock(ActiveSessionDirectory.class);
    private final VehicleDirectory vehicles = mock(VehicleDirectory.class);
    private final HolidayCalendar holidays = mock(HolidayCalendar.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private RuleEngineProperties properties = properties(true);
    private ViolationEvaluator evaluator;

    private static RuleEngineProperties properties(boolean lookupEnabled) {
        return new RuleEngineProperties(0.85, 0.90, 0.5, 10, 30, 5, 5, 10,
                BigDecimal.valueOf(900), BigDecimal.valueOf(450), BigDecimal.valueOf(700), BigDecimal.valueOf(900),
                BigDecimal.valueOf(700), 60000, 20, 15, 60000, 10, 5, 60, lookupEnabled);
    }

    @BeforeEach
    void setUp() {
        build(properties);
        when(ruleCache.rulesOf(ZONE_ID)).thenReturn(List.of());
        when(vehicles.lookup(anyString(), anyString())).thenReturn(Optional.empty());
        when(sessions.startOfActiveSession(anyString(), anyString())).thenReturn(Optional.empty());
        when(permits.holdsAll(anyString(), anyString(), any(), any())).thenReturn(true);
    }

    private void build(RuleEngineProperties props) {
        properties = props;
        EngineMetrics metrics = new EngineMetrics(registry);
        evaluator = new ViolationEvaluator(ruleCache, new RuleResolver(holidays, metrics), permits, sessions, vehicles,
                props, metrics);
    }

    private static ParkingSpot spot(String type, String permitType, int maxMinutes) {
        Polygon square = new Polygon(List.of(new Point(0, 0), new Point(10, 0), new Point(10, 10), new Point(0, 10)));
        return new ParkingSpot(UUID.randomUUID(), ZONE_ID, "A1", type, permitType, square, 0, 0, maxMinutes);
    }

    private static ParkingSpot spot(String type) {
        return spot(type, null, 0);
    }

    private static ZoneInfo zone(int maxMinutes) {
        return new ZoneInfo("Storgatan 1", maxMinutes, "SE", ZoneInfo.DEFAULT_TIMEZONE);
    }

    private Optional<ViolationType> evaluate(ParkingSpot spot, boolean insideLines, String plate, VehicleClass detected, int zoneMax) {
        return evaluator.evaluate(new Observation(zone(zoneMax), spot, insideLines, plate, detected, NOW));
    }

    private Optional<ViolationType> evaluate(ParkingSpot spot) {
        return evaluate(spot, true, PLATE, VehicleClass.CAR, 0);
    }

    private void withRules(ZoneRule... rules) {
        when(ruleCache.rulesOf(ZONE_ID)).thenReturn(List.of(rules));
    }

    private void registryKnows(VehicleClass vehicleClass, Fuel fuel) {
        when(vehicles.lookup(PLATE, "SE")).thenReturn(Optional.of(new VehicleInfo(vehicleClass, fuel)));
    }

    // ---- valid parking ------------------------------------------------------------------------

    @Test
    void aCarInsideTheLinesOfAPaidSpotIsFine() {
        assertThat(evaluate(spot("paid"))).isEmpty();
        verifyNoInteractions(vehicles, sessions, permits);
    }

    @Test
    void everyEvaluationIsCounted() {
        evaluate(spot("paid"));
        evaluate(spot("paid"));

        assertThat(registry.counter("ruleengine.observations.evaluated").count()).isEqualTo(2.0);
    }

    // ---- 1) NO_PARKING ------------------------------------------------------------------------

    @Test
    void aNoParkingSpotIsAViolation() {
        assertThat(evaluate(spot("no_parking"))).contains(ViolationType.NO_PARKING);
    }

    @Test
    void aNoParkingRuleMakesAnyRegularSpotANoParkingSpot() {
        withRules(Rules.rule().noParking().build());

        assertThat(evaluate(spot("paid"))).contains(ViolationType.NO_PARKING);
    }

    @Test
    void aNoParkingRuleOutsideItsWindowDoesNotApply() {
        withRules(Rules.rule().window("22:00", "06:00").noParking().build());

        assertThat(evaluate(spot("paid"))).isEmpty();
    }

    @Test
    void aNoParkingRuleForTrucksIsNotAViolationForCars() {
        withRules(Rules.rule().vehicles("truck").noParking().build());

        assertThat(evaluate(spot("paid"), true, PLATE, VehicleClass.CAR, 0)).isEmpty();
        assertThat(evaluate(spot("paid"), true, PLATE, VehicleClass.TRUCK, 0)).contains(ViolationType.NO_PARKING);
    }

    @Test
    void noParkingComesBeforeEverythingElse() {
        withRules(Rules.rule().noParking().permit("resident").maxMinutes(1).build());
        registryKnows(VehicleClass.CAR, Fuel.OTHER);
        when(permits.holdsAll(anyString(), anyString(), any(), any())).thenReturn(false);

        assertThat(evaluate(spot("ev"), false, PLATE, VehicleClass.CAR, 0)).contains(ViolationType.NO_PARKING);
    }

    // ---- 2) WRONG_VEHICLE_TYPE ----------------------------------------------------------------

    @Test
    void aCombustionCarInAnEvSpotIsAWrongVehicleType() {
        registryKnows(VehicleClass.CAR, Fuel.OTHER);

        assertThat(evaluate(spot("ev"))).contains(ViolationType.WRONG_VEHICLE_TYPE);
        verify(vehicles).lookup(PLATE, "SE");
    }

    @Test
    void anElectricCarInAnEvSpotIsFine() {
        registryKnows(VehicleClass.CAR, Fuel.ELECTRIC);

        assertThat(evaluate(spot("ev"))).isEmpty();
    }

    @Test
    void aHybridIsNotElectric() {
        when(vehicles.lookup(PLATE, "SE")).thenReturn(Optional.of(new VehicleInfo(VehicleClass.CAR, Fuel.fromRegistry("El/Bensin"))));

        assertThat(evaluate(spot("ev"))).contains(ViolationType.WRONG_VEHICLE_TYPE);
    }

    @Test
    void anEvSpotCannotBeJudgedWithoutRegistryData() {
        // the registry does not know the plate
        assertThat(evaluate(spot("ev"))).isEmpty();
        // it knows the plate but not the drive
        registryKnows(VehicleClass.CAR, Fuel.UNKNOWN);
        assertThat(evaluate(spot("ev"))).isEmpty();
    }

    @Test
    void anEvSpotCannotBeJudgedWhenTheLookupIsSwitchedOffOrThePlateIsUnknown() {
        registryKnows(VehicleClass.CAR, Fuel.OTHER);
        build(properties(false));
        assertThat(evaluate(spot("ev"))).isEmpty();

        build(properties(true));
        assertThat(evaluate(spot("ev"), true, null, VehicleClass.CAR, 0)).isEmpty();
        verify(vehicles, never()).lookup(any(), any());
    }

    @Test
    void aWrongVehicleTypeComesBeforeAWrongPermit() {
        registryKnows(VehicleClass.CAR, Fuel.OTHER);
        withRules(Rules.rule().permit("resident").build());
        when(permits.holdsAll(anyString(), anyString(), any(), any())).thenReturn(false);

        assertThat(evaluate(spot("ev"))).contains(ViolationType.WRONG_VEHICLE_TYPE);
    }

    // ---- 3) WRONG_PERMIT ----------------------------------------------------------------------

    @Test
    void aPermitSpotNeedsAPermitOfItsType() {
        when(permits.holdsAll(PLATE, ZONE_ID, Set.of("resident"), NOW)).thenReturn(false);

        assertThat(evaluate(spot("permit", "resident", 0))).contains(ViolationType.WRONG_PERMIT);

        when(permits.holdsAll(PLATE, ZONE_ID, Set.of("resident"), NOW)).thenReturn(true);

        assertThat(evaluate(spot("permit", "resident", 0))).isEmpty();
    }

    @Test
    void aPermitSpotWithoutARequiredTypeNeedsNoPermit() {
        assertThat(evaluate(spot("permit", null, 0))).isEmpty();
        assertThat(evaluate(spot("permit", "  ", 0))).isEmpty();
        verifyNoInteractions(permits);
    }

    @Test
    void aDisabledSpotNeedsADisabledPermit() {
        when(permits.holdsAll(PLATE, ZONE_ID, Set.of("disabled"), NOW)).thenReturn(false);

        assertThat(evaluate(spot("disabled"))).contains(ViolationType.WRONG_PERMIT);

        when(permits.holdsAll(PLATE, ZONE_ID, Set.of("disabled"), NOW)).thenReturn(true);

        assertThat(evaluate(spot("disabled"))).isEmpty();
    }

    @Test
    void theDecidingRulesPermitIsRequiredOnAnyRegularSpot() {
        withRules(Rules.rule().permit("Resident").build());
        when(permits.holdsAll(PLATE, ZONE_ID, Set.of("resident"), NOW)).thenReturn(false);

        assertThat(evaluate(spot("paid"))).contains(ViolationType.WRONG_PERMIT);
    }

    @Test
    void aPermitRuleOutsideItsWindowOrForOtherVehiclesDoesNotApply() {
        withRules(
                Rules.rule().priority(1).weekdays(6, 7).permit("resident").build(),
                Rules.rule().priority(2).vehicles("truck").permit("loading").build());

        assertThat(evaluate(spot("paid"))).isEmpty();
        verifyNoInteractions(permits);
    }

    @Test
    void spotAndRulePermitsAreCheckedTogetherInOneQuery() {
        withRules(Rules.rule().permit("loading").build());
        when(permits.holdsAll(PLATE, ZONE_ID, Set.of("resident", "loading"), NOW)).thenReturn(false);

        assertThat(evaluate(spot("permit", "resident", 0))).contains(ViolationType.WRONG_PERMIT);
        verify(permits, times(1)).holdsAll(anyString(), anyString(), any(), any());
    }

    @Test
    void theSamePermitFromSpotAndRuleIsOneRequirement() {
        withRules(Rules.rule().permit("RESIDENT").build());

        evaluate(spot("permit", "resident", 0));

        verify(permits).holdsAll(eq(PLATE), eq(ZONE_ID), eq(Set.of("resident")), eq(NOW));
    }

    @Test
    void aPermitCannotBeCheckedForAnUnknownPlate() {
        assertThat(evaluate(spot("permit", "resident", 0), true, null, VehicleClass.CAR, 0)).isEmpty();
        verifyNoInteractions(permits);
    }

    // ---- 4) OVERSTAY --------------------------------------------------------------------------

    @Test
    void stayingLongerThanTheZoneLimitIsAnOverstay() {
        when(sessions.startOfActiveSession(PLATE, ZONE_ID)).thenReturn(Optional.of(NOW.minusSeconds(121 * 60)));

        assertThat(evaluate(spot("paid"), true, PLATE, VehicleClass.CAR, 120)).contains(ViolationType.OVERSTAY);
    }

    @Test
    void exactlyTheLimitIsNotYetAnOverstay() {
        when(sessions.startOfActiveSession(PLATE, ZONE_ID)).thenReturn(Optional.of(NOW.minusSeconds(120 * 60)));

        assertThat(evaluate(spot("paid"), true, PLATE, VehicleClass.CAR, 120)).isEmpty();
    }

    @Test
    void theRulesLimitOverridesTheSpotAndTheZone() {
        withRules(Rules.rule().maxMinutes(30).build());
        when(sessions.startOfActiveSession(PLATE, ZONE_ID)).thenReturn(Optional.of(NOW.minusSeconds(31 * 60)));

        assertThat(evaluate(spot("timed", null, 240), true, PLATE, VehicleClass.CAR, 480)).contains(ViolationType.OVERSTAY);
    }

    @Test
    void aRuleLimitLongerThanTheZoneDefaultRelaxesIt() {
        withRules(Rules.rule().maxMinutes(300).build());
        when(sessions.startOfActiveSession(PLATE, ZONE_ID)).thenReturn(Optional.of(NOW.minusSeconds(200 * 60)));

        assertThat(evaluate(spot("paid"), true, PLATE, VehicleClass.CAR, 120)).isEmpty();
    }

    @Test
    void timedAndLoadingSpotsUseTheirOwnLimitBeforeTheZones() {
        when(sessions.startOfActiveSession(PLATE, ZONE_ID)).thenReturn(Optional.of(NOW.minusSeconds(61 * 60)));

        assertThat(evaluate(spot("timed", null, 60), true, PLATE, VehicleClass.CAR, 480)).contains(ViolationType.OVERSTAY);
        assertThat(evaluate(spot("loading", null, 60), true, PLATE, VehicleClass.CAR, 480)).contains(ViolationType.OVERSTAY);
    }

    @Test
    void otherSpotTypesIgnoreTheirMaxMinutesColumn() {
        when(sessions.startOfActiveSession(PLATE, ZONE_ID)).thenReturn(Optional.of(NOW.minusSeconds(61 * 60)));

        assertThat(evaluate(spot("paid", null, 30), true, PLATE, VehicleClass.CAR, 0)).isEmpty();
        verifyNoInteractions(sessions);
    }

    @Test
    void withoutAnyLimitNoSessionIsRead() {
        assertThat(evaluate(spot("paid"), true, PLATE, VehicleClass.CAR, 0)).isEmpty();
        verifyNoInteractions(sessions);
    }

    @Test
    void withoutAnActiveSessionOverstayCannotBeJudged() {
        assertThat(evaluate(spot("paid"), true, PLATE, VehicleClass.CAR, 120)).isEmpty();
        verify(sessions).startOfActiveSession(PLATE, ZONE_ID);
    }

    @Test
    void anUnknownPlateCannotOverstay() {
        assertThat(evaluate(spot("paid"), true, null, VehicleClass.CAR, 120)).isEmpty();
        verifyNoInteractions(sessions);
    }

    @Test
    void overstayComesBeforeBoundary() {
        when(sessions.startOfActiveSession(PLATE, ZONE_ID)).thenReturn(Optional.of(NOW.minusSeconds(500 * 60)));

        assertThat(evaluate(spot("paid"), false, PLATE, VehicleClass.CAR, 120)).contains(ViolationType.OVERSTAY);
    }

    @Test
    void aWrongPermitComesBeforeOverstay() {
        when(permits.holdsAll(anyString(), anyString(), any(), any())).thenReturn(false);
        when(sessions.startOfActiveSession(PLATE, ZONE_ID)).thenReturn(Optional.of(NOW.minusSeconds(500 * 60)));

        assertThat(evaluate(spot("permit", "resident", 0), true, PLATE, VehicleClass.CAR, 120)).contains(ViolationType.WRONG_PERMIT);
        verifyNoInteractions(sessions);
    }

    // ---- 5) BOUNDARY_EXCEEDED -----------------------------------------------------------------

    @Test
    void notBeingInsideTheLinesIsABoundaryViolationOfTheLastResort() {
        assertThat(evaluate(spot("paid"), false, PLATE, VehicleClass.CAR, 0)).contains(ViolationType.BOUNDARY_EXCEEDED);
    }

    @Test
    void aBoundaryViolationNeedsNoPlate() {
        assertThat(evaluate(spot("paid"), false, null, null, 0)).contains(ViolationType.BOUNDARY_EXCEEDED);
    }

    // ---- registry usage -----------------------------------------------------------------------

    @Test
    void theRegistryIsOnlyAskedWhenADecisionNeedsIt() {
        withRules(Rules.rule().noParking().maxMinutes(10).permit("resident").build());

        evaluate(spot("paid"));
        evaluate(spot("timed", null, 30));
        evaluate(spot("permit", "resident", 0));

        verify(vehicles, never()).lookup(any(), any());
    }

    @Test
    void theRegistryIsAskedOncePerObservationEvenForAnEvSpotWithFilteredRules() {
        registryKnows(VehicleClass.TRUCK, Fuel.ELECTRIC);
        withRules(
                Rules.rule().priority(1).vehicles("truck").permit("loading").build(),
                Rules.rule().priority(2).vehicles("ev").maxMinutes(15).build());

        evaluate(spot("ev"), true, PLATE, null, 0);

        verify(vehicles, times(1)).lookup(PLATE, "SE");
    }

    @Test
    void aFailingRegistryOnlyMeansCannotJudge() {
        withRules(Rules.rule().vehicles("truck").noParking().build());

        assertThat(evaluate(spot("ev"), true, PLATE, null, 0)).isEmpty();
    }
}
