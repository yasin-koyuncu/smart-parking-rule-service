package com.parkview.ruleengine.engine;

import com.parkview.ruleengine.client.ActiveSessionDirectory;
import com.parkview.ruleengine.client.HolidayCalendar;
import com.parkview.ruleengine.client.VehicleDirectory;
import com.parkview.ruleengine.config.RuleEngineProperties;
import com.parkview.ruleengine.domain.ParkingSpot;
import com.parkview.ruleengine.domain.VehicleClass;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The plain-language rule text that is frozen into the evidence of a fine: which rule decided, in words, from the rule that was evaluated. */
class RuleSummariesTest {

    // Monday 2026-10-12 10:00 Stockholm
    private static final Instant NOW = Instant.parse("2026-10-12T08:00:00Z");
    private static final String ZONE_ID = "zone-1";
    private static final String PLATE = "ABC123";

    private final RuleCacheService ruleCache = mock(RuleCacheService.class);
    private final PermitService permits = mock(PermitService.class);
    private final ActiveSessionDirectory sessions = mock(ActiveSessionDirectory.class);
    private final VehicleDirectory vehicles = mock(VehicleDirectory.class);
    private final HolidayCalendar holidays = mock(HolidayCalendar.class);
    private ViolationEvaluator evaluator;

    @BeforeEach
    void setUp() {
        EngineMetrics metrics = new EngineMetrics(new SimpleMeterRegistry());
        RuleEngineProperties properties = new RuleEngineProperties(0.85, 0.90, 0.5, 10, 30, 5, 5, 10,
                BigDecimal.valueOf(900), BigDecimal.valueOf(450), BigDecimal.valueOf(700), BigDecimal.valueOf(900),
                BigDecimal.valueOf(700), 60000, 20, 15, 60000, 10, 5, 60, true);
        evaluator = new ViolationEvaluator(ruleCache, new RuleResolver(holidays, metrics), permits, sessions, vehicles, properties, metrics);
        when(ruleCache.rulesOf(ZONE_ID)).thenReturn(List.of());
        when(vehicles.lookup(anyString(), anyString())).thenReturn(Optional.empty());
        when(sessions.startOfActiveSession(anyString(), anyString())).thenReturn(Optional.empty());
        when(permits.holdsAll(anyString(), anyString(), any(), any())).thenReturn(true);
    }

    private static ParkingSpot spot(String type, String permitType, int maxMinutes) {
        Polygon square = new Polygon(List.of(new Point(0, 0), new Point(10, 0), new Point(10, 10), new Point(0, 10)));
        return new ParkingSpot(UUID.randomUUID(), ZONE_ID, "A1", type, permitType, square, 0, 0, maxMinutes);
    }

    private Finding assess(ParkingSpot spot, boolean insideLines, int zoneMax, ZoneRule... rules) {
        when(ruleCache.rulesOf(ZONE_ID)).thenReturn(List.of(rules));
        ZoneInfo zone = new ZoneInfo("Storgatan 1", zoneMax, "SE", ZoneInfo.DEFAULT_TIMEZONE);
        return evaluator.assess(new Observation(zone, spot, insideLines, PLATE, VehicleClass.CAR, NOW)).orElseThrow();
    }

    @Test
    void assessAndEvaluateAgree() {
        ParkingSpot noParking = spot("no_parking", null, 0);
        ZoneInfo zone = new ZoneInfo("Storgatan 1", 0, "SE", ZoneInfo.DEFAULT_TIMEZONE);
        Observation observation = new Observation(zone, noParking, true, PLATE, VehicleClass.CAR, NOW);

        assertThat(evaluator.evaluate(observation)).contains(ViolationType.NO_PARKING);
        assertThat(evaluator.assess(observation).map(Finding::type)).contains(ViolationType.NO_PARKING);
    }

    @Test
    void aSpotThatForbidsParkingSaysSo() {
        Finding finding = assess(spot("no_parking", null, 0), true, 0);

        assertThat(finding.type()).isEqualTo(ViolationType.NO_PARKING);
        assertThat(finding.ruleSummary()).isEqualTo("Parking is not allowed in this spot (spot type: no parking).");
    }

    @Test
    void aNoParkingRuleIsDescribedWithItsDaysWindowAndHolidayHandling() {
        Finding finding = assess(spot("paid", null, 0), true, 0,
                Rules.rule().weekdays(1, 2, 3, 4, 5).window("08:00", "18:00").holidays(false).noParking().build());

        assertThat(finding.ruleSummary())
                .isEqualTo("Parking is not allowed while this applies: zone rule \"rule\" (Mon-Fri 08:00-18:00, public holidays excluded).");
    }

    @Test
    void daysAreWrittenAsRunsAndAWholeWeekAsEveryDay() {
        Finding weekend = assess(spot("paid", null, 0), true, 0,
                Rules.rule().weekdays(1, 2, 3, 6, 7).window("00:00", "23:59").noParking().build());
        Finding allWeek = assess(spot("paid", null, 0), true, 0, Rules.rule().noParking().build());

        assertThat(weekend.ruleSummary()).contains("(Mon-Wed, Sat-Sun 00:00-midnight)");
        assertThat(allWeek.ruleSummary()).contains("(every day 00:00-midnight)");
    }

    @Test
    void aVehicleFilterIsPartOfTheText() {
        Finding finding = assess(spot("paid", null, 0), true, 0,
                Rules.rule().vehicles("car", "truck").window("10:00", "10:00").noParking().build());

        assertThat(finding.ruleSummary()).contains("every day all day, vehicles: car, truck)");
    }

    @Test
    void aMissingPermitNamesWhatTheSpotAndTheRuleDemand() {
        when(permits.holdsAll(anyString(), anyString(), any(), any())).thenReturn(false);

        Finding spotOnly = assess(spot("permit", "Resident", 0), true, 0);
        Finding both = assess(spot("permit", "resident", 0), true, 0, Rules.rule().permit("disabled").build());

        assertThat(spotOnly.type()).isEqualTo(ViolationType.WRONG_PERMIT);
        assertThat(spotOnly.ruleSummary()).isEqualTo("No valid permit was registered for the plate: the spot requires a Resident permit.");
        assertThat(both.ruleSummary()).startsWith("No valid permit was registered for the plate: the spot requires a resident permit; zone rule")
                .endsWith("requires a disabled permit.");
    }

    @Test
    void anOverstayNamesTheLimitAndWhereItCameFrom() {
        when(sessions.startOfActiveSession(PLATE, ZONE_ID)).thenReturn(Optional.of(NOW.minusSeconds(3 * 3600)));

        Finding byRule = assess(spot("paid", null, 0), true, 240, Rules.rule().maxMinutes(60).build());
        Finding bySpot = assess(spot("timed", null, 90), true, 240);
        Finding byZone = assess(spot("paid", null, 0), true, 120);

        assertThat(byRule.type()).isEqualTo(ViolationType.OVERSTAY);
        assertThat(byRule.ruleSummary()).startsWith("The stay limit is 60 minutes (zone rule").endsWith("and the parking session had lasted longer.");
        assertThat(bySpot.ruleSummary()).isEqualTo("The stay limit is 90 minutes (the limit of this spot) and the parking session had lasted longer.");
        assertThat(byZone.ruleSummary()).isEqualTo("The stay limit is 120 minutes (the limit of the zone) and the parking session had lasted longer.");
    }

    @Test
    void aBoundaryViolationNamesTheSpot() {
        Finding finding = assess(spot("paid", null, 0), false, 0);

        assertThat(finding.type()).isEqualTo(ViolationType.BOUNDARY_EXCEEDED);
        assertThat(finding.ruleSummary()).isEqualTo("The vehicle was not inside the lines of the parking spot (spot A1).");
    }

    @Test
    void theTextDoesNotDependOnTheRuleIdSoLaterEditsCannotChangeIt() {
        ZoneRule rule = Rules.rule().id("11111111-1111-1111-1111-111111111111").noParking().build();

        Finding finding = assess(spot("paid", null, 0), true, 0, rule);

        assertThat(finding.ruleSummary()).doesNotContain(rule.id().toString());
    }
}
