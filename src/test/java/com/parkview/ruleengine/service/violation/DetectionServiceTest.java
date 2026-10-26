package com.parkview.ruleengine.service.violation;

import com.parkview.ruleengine.domain.type.ResolutionReason;
import com.parkview.ruleengine.domain.type.VehicleClass;
import com.parkview.ruleengine.domain.type.ViolationType;
import com.parkview.ruleengine.domain.value.BoundaryCheckResult;
import com.parkview.ruleengine.domain.value.ParkingSpot;
import com.parkview.ruleengine.domain.value.ZoneInfo;
import com.parkview.ruleengine.dto.event.VehicleDetectedEvent;
import com.parkview.ruleengine.engine.Finding;
import com.parkview.ruleengine.engine.Observation;
import com.parkview.ruleengine.engine.ViolationEvaluator;
import com.parkview.ruleengine.geometry.Polygon;
import com.parkview.ruleengine.service.TestFixtures;
import com.parkview.ruleengine.service.cache.RuleCacheService;
import com.parkview.ruleengine.service.cache.SpotCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DetectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");
    private static final String ZONE = TestFixtures.ZONE;
    private static final String PLATE = TestFixtures.PLATE;
    private static final List<List<Double>> SQUARE =
            List.of(List.of(0.0, 0.0), List.of(0.0, 10.0), List.of(10.0, 10.0), List.of(10.0, 0.0));

    private final SpotCacheService spotCache = mock(SpotCacheService.class);
    private final RuleCacheService ruleCache = mock(RuleCacheService.class);
    private final BoundaryService boundary = mock(BoundaryService.class);
    private final ViolationEvaluator evaluator = mock(ViolationEvaluator.class);
    private final ViolationService violations = mock(ViolationService.class);
    private final ParkingSpot spot = TestFixtures.spot("permit", "resident", 0, 0);
    private final ZoneInfo zoneInfo = ZoneInfo.unknown();
    private DetectionService service;

    @BeforeEach
    void setUp() {
        service = new DetectionService(spotCache, ruleCache, boundary, evaluator, violations, TestFixtures.properties(), Clock.fixed(NOW, ZoneOffset.UTC));
        when(spotCache.spotsOf(ZONE, null)).thenReturn(List.of(spot));
        // an event of a producer from before sites names only the zone; the zone of the fixtures is its own site
        when(ruleCache.siteIdOf(ZONE)).thenReturn(ZONE);
        when(ruleCache.placeOf(ZONE, ZONE)).thenReturn(zoneInfo);
    }

    private static VehicleDetectedEvent event(String plate, List<List<Double>> polygon, String vehicleClass) {
        return new VehicleDetectedEvent("cam-1", ZONE, plate, polygon, null, null, 0.9, 0.9, NOW.toEpochMilli(), vehicleClass, null);
    }

    private static VehicleDetectedEvent eventOnView(UUID viewId) {
        return new VehicleDetectedEvent("cam-1", ZONE, PLATE, SQUARE, null, null, 0.9, 0.9, NOW.toEpochMilli(), null, viewId);
    }

    private void parkedInSpot(boolean insideLines) {
        when(boundary.check(any(Polygon.class), eq(List.of(spot))))
                .thenReturn(Optional.of(new BoundaryCheckResult(spot, 1.0, 1.0, insideLines)));
    }

    @Test
    void aViolationIsConfirmedAndEverythingElseOfThePlateIsCorrected() {
        parkedInSpot(true);
        when(evaluator.assess(any(Observation.class))).thenReturn(Optional.of(new Finding(ViolationType.WRONG_PERMIT, "summary")));

        service.process(event("abc 123", SQUARE, null));

        InOrder order = inOrder(violations);
        order.verify(violations).confirm(spot, ViolationType.WRONG_PERMIT, PLATE, "cam-1", "summary", NOW);
        order.verify(violations).resolveOthers(PLATE, ZONE, ZONE, spot.id(), ViolationType.WRONG_PERMIT, ResolutionReason.CORRECTED);
    }

    @Test
    void aDifferentTypeThanBeforeOpensTheNewViolationAndResolvesTheOthers() {
        parkedInSpot(true);
        when(evaluator.assess(any(Observation.class))).thenReturn(Optional.of(new Finding(ViolationType.OVERSTAY, "summary")));

        service.process(event(PLATE, SQUARE, "car"));

        verify(violations).confirm(spot, ViolationType.OVERSTAY, PLATE, "cam-1", "summary", NOW);
        verify(violations).resolveOthers(PLATE, ZONE, ZONE, spot.id(), ViolationType.OVERSTAY, ResolutionReason.CORRECTED);
    }

    @Test
    void theEvaluatorGetsTheSpotTheZoneThePlateTheDetectorClassAndTheClockTime() {
        parkedInSpot(false);
        when(evaluator.assess(any(Observation.class))).thenReturn(Optional.empty());

        service.process(event("abc 123", SQUARE, "motorcycle"));

        ArgumentCaptor<Observation> captured = ArgumentCaptor.forClass(Observation.class);
        verify(evaluator).assess(captured.capture());
        Observation observation = captured.getValue();
        assertThat(observation.spot()).isEqualTo(spot);
        assertThat(observation.zone()).isEqualTo(zoneInfo);
        assertThat(observation.insideLines()).isFalse();
        assertThat(observation.plate()).isEqualTo(PLATE);
        assertThat(observation.detectedClass()).isEqualTo(VehicleClass.MOTORCYCLE);
        assertThat(observation.at()).isEqualTo(NOW);
    }

    @Test
    void anUnknownDetectorClassIsPassedOnAsUnknown() {
        parkedInSpot(true);
        when(evaluator.assess(any(Observation.class))).thenReturn(Optional.empty());

        service.process(event(PLATE, SQUARE, "hovercraft"));

        ArgumentCaptor<Observation> captured = ArgumentCaptor.forClass(Observation.class);
        verify(evaluator).assess(captured.capture());
        assertThat(captured.getValue().detectedClass()).isNull();
    }

    @Test
    void noViolationResolvesOpenViolationsAsCorrected() {
        parkedInSpot(true);
        when(evaluator.assess(any(Observation.class))).thenReturn(Optional.empty());

        service.process(event(PLATE, SQUARE, null));

        verify(violations, never()).confirm(any(), any(), anyString(), anyString(), any(), any());
        verify(violations).resolveOthers(PLATE, ZONE, ZONE, null, null, ResolutionReason.CORRECTED);
    }

    @Test
    void aVehicleOutsideAllSpotsResolvesOpenViolationsAsLeftWithoutEvaluating() {
        when(boundary.check(any(Polygon.class), any())).thenReturn(Optional.empty());

        service.process(event(PLATE, SQUARE, null));

        verify(violations).resolveOthers(PLATE, ZONE, ZONE, null, null, ResolutionReason.LEFT);
        verifyNoInteractions(evaluator);
    }

    @Test
    void anObservationAtASiteWithoutAZoneIsMatchedAgainstTheSpotsOfTheSite() {
        when(spotCache.spotsOf("s1", null)).thenReturn(List.of());

        service.process(new VehicleDetectedEvent("cam-9", null, PLATE, SQUARE, null, null, 0.9, 0.9, NOW.toEpochMilli(), null, null, "s1"));

        verify(spotCache).spotsOf("s1", null);
        verifyNoInteractions(boundary, evaluator, violations);
    }

    @Test
    void observationsWithoutAPlateAreIgnored() {
        service.process(event(null, SQUARE, null));
        service.process(event("  ", SQUARE, null));

        verifyNoInteractions(boundary, evaluator, violations);
    }

    @Test
    void zonesWithoutSpotsAreSkipped() {
        when(spotCache.spotsOf(ZONE, null)).thenReturn(List.of());

        service.process(event(PLATE, SQUARE, null));

        verifyNoInteractions(boundary, evaluator, violations);
    }

    @Test
    void anUnusablePolygonIsDiscarded() {
        service.process(event(PLATE, List.of(List.of(0.0, 0.0), List.of(1.0), List.of(2.0, 2.0)), null));

        verifyNoInteractions(boundary, evaluator, violations);
    }

    @Test
    void anObservationOnAViewIsMatchedAgainstTheSpotsOfThatViewOnly() {
        UUID viewId = UUID.randomUUID();
        ParkingSpot viewSpot = TestFixtures.spot("no_parking", null, 0, 0);
        when(spotCache.spotsOf(ZONE, viewId)).thenReturn(List.of(viewSpot));
        when(boundary.check(any(Polygon.class), eq(List.of(viewSpot))))
                .thenReturn(Optional.of(new BoundaryCheckResult(viewSpot, 1.0, 1.0, true)));
        when(evaluator.assess(any(Observation.class))).thenReturn(Optional.of(new Finding(ViolationType.NO_PARKING, "summary")));

        service.process(eventOnView(viewId));

        verify(spotCache).spotsOf(ZONE, viewId);
        verify(spotCache, never()).spotsOf(ZONE, null);
        verify(boundary, never()).check(any(Polygon.class), eq(List.of(spot)));
        verify(violations).confirm(viewSpot, ViolationType.NO_PARKING, PLATE, "cam-1", "summary", NOW);
    }

    @Test
    void aLegacyObservationNeverSeesTheSpotsOfAView() {
        parkedInSpot(true);
        when(evaluator.assess(any(Observation.class))).thenReturn(Optional.empty());

        service.process(event(PLATE, SQUARE, null));

        verify(spotCache).spotsOf(ZONE, null);
        verify(spotCache, never()).spotsOf(eq(ZONE), any(UUID.class));
    }

    @Test
    void aViewWithoutSpotsIsSkippedEvenWhenTheZoneHasLegacySpots() {
        UUID viewId = UUID.randomUUID();
        when(spotCache.spotsOf(ZONE, viewId)).thenReturn(List.of());

        service.process(eventOnView(viewId));

        verifyNoInteractions(boundary, evaluator, violations);
    }


    private static VehicleDetectedEvent eventAt(Instant observed) {
        return new VehicleDetectedEvent("cam-1", ZONE, PLATE, SQUARE, null, null, 0.9, 0.9, observed.toEpochMilli(), null, null);
    }

    @Test
    void anObservationOlderThanTheMaximumAgeIsDroppedWithoutTouchingAnything() {
        parkedInSpot(true);

        service.process(eventAt(NOW.minusSeconds(601)));

        verifyNoInteractions(violations, evaluator, boundary);
    }

    @Test
    void anObservationJustWithinTheMaximumAgeIsProcessedWithItsOwnTime() {
        parkedInSpot(true);
        Instant seen = NOW.minusSeconds(600);
        when(evaluator.assess(any(Observation.class))).thenReturn(Optional.of(new Finding(ViolationType.WRONG_PERMIT, "summary")));

        service.process(eventAt(seen));

        ArgumentCaptor<Observation> captured = ArgumentCaptor.forClass(Observation.class);
        verify(evaluator).assess(captured.capture());
        assertThat(captured.getValue().at()).isEqualTo(seen);
        verify(violations).confirm(spot, ViolationType.WRONG_PERMIT, PLATE, "cam-1", "summary", seen);
    }

    @Test
    void aTimeInTheFutureIsClampedToNowAndAMissingTimeCountsAsNow() {
        parkedInSpot(true);
        when(evaluator.assess(any(Observation.class))).thenReturn(Optional.of(new Finding(ViolationType.WRONG_PERMIT, "summary")));

        service.process(eventAt(NOW.plusSeconds(3600)));
        service.process(new VehicleDetectedEvent("cam-1", ZONE, PLATE, SQUARE, null, null, 0.9, 0.9, 0L, null, null));

        verify(violations, org.mockito.Mockito.times(2)).confirm(spot, ViolationType.WRONG_PERMIT, PLATE, "cam-1", "summary", NOW);
    }
}
