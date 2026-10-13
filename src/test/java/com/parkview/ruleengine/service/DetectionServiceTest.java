package com.parkview.ruleengine.service;

import com.parkview.ruleengine.domain.BoundaryCheckResult;
import com.parkview.ruleengine.domain.ParkingSpot;
import com.parkview.ruleengine.domain.ResolutionReason;
import com.parkview.ruleengine.domain.VehicleClass;
import com.parkview.ruleengine.domain.ViolationType;
import com.parkview.ruleengine.domain.ZoneInfo;
import com.parkview.ruleengine.dto.event.VehicleDetectedEvent;
import com.parkview.ruleengine.engine.Observation;
import com.parkview.ruleengine.engine.ViolationEvaluator;
import com.parkview.ruleengine.geometry.Polygon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

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
        service = new DetectionService(spotCache, ruleCache, boundary, evaluator, violations, Clock.fixed(NOW, ZoneOffset.UTC));
        when(spotCache.spotsOf(ZONE)).thenReturn(List.of(spot));
        when(ruleCache.zoneOf(ZONE)).thenReturn(zoneInfo);
    }

    private static VehicleDetectedEvent event(String plate, List<List<Double>> polygon, String vehicleClass) {
        return new VehicleDetectedEvent("cam-1", ZONE, plate, polygon, null, null, 0.9, 0.9, NOW.toEpochMilli(), vehicleClass);
    }

    private void parkedInSpot(boolean insideLines) {
        when(boundary.check(any(Polygon.class), eq(List.of(spot))))
                .thenReturn(Optional.of(new BoundaryCheckResult(spot, 1.0, 1.0, insideLines)));
    }

    @Test
    void aViolationIsConfirmedAndEverythingElseOfThePlateIsCorrected() {
        parkedInSpot(true);
        when(evaluator.evaluate(any(Observation.class))).thenReturn(Optional.of(ViolationType.WRONG_PERMIT));

        service.process(event("abc 123", SQUARE, null));

        InOrder order = inOrder(violations);
        order.verify(violations).confirm(spot, ViolationType.WRONG_PERMIT, PLATE, "cam-1");
        order.verify(violations).resolveOthers(PLATE, ZONE, spot.id(), ViolationType.WRONG_PERMIT, ResolutionReason.CORRECTED);
    }

    @Test
    void aDifferentTypeThanBeforeOpensTheNewViolationAndResolvesTheOthers() {
        parkedInSpot(true);
        when(evaluator.evaluate(any(Observation.class))).thenReturn(Optional.of(ViolationType.OVERSTAY));

        service.process(event(PLATE, SQUARE, "car"));

        verify(violations).confirm(spot, ViolationType.OVERSTAY, PLATE, "cam-1");
        verify(violations).resolveOthers(PLATE, ZONE, spot.id(), ViolationType.OVERSTAY, ResolutionReason.CORRECTED);
    }

    @Test
    void theEvaluatorGetsTheSpotTheZoneThePlateTheDetectorClassAndTheClockTime() {
        parkedInSpot(false);
        when(evaluator.evaluate(any(Observation.class))).thenReturn(Optional.empty());

        service.process(event("abc 123", SQUARE, "motorcycle"));

        ArgumentCaptor<Observation> captured = ArgumentCaptor.forClass(Observation.class);
        verify(evaluator).evaluate(captured.capture());
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
        when(evaluator.evaluate(any(Observation.class))).thenReturn(Optional.empty());

        service.process(event(PLATE, SQUARE, "hovercraft"));

        ArgumentCaptor<Observation> captured = ArgumentCaptor.forClass(Observation.class);
        verify(evaluator).evaluate(captured.capture());
        assertThat(captured.getValue().detectedClass()).isNull();
    }

    @Test
    void noViolationResolvesOpenViolationsAsCorrected() {
        parkedInSpot(true);
        when(evaluator.evaluate(any(Observation.class))).thenReturn(Optional.empty());

        service.process(event(PLATE, SQUARE, null));

        verify(violations, never()).confirm(any(), any(), anyString(), anyString());
        verify(violations).resolveOthers(PLATE, ZONE, null, null, ResolutionReason.CORRECTED);
    }

    @Test
    void aVehicleOutsideAllSpotsResolvesOpenViolationsAsLeftWithoutEvaluating() {
        when(boundary.check(any(Polygon.class), any())).thenReturn(Optional.empty());

        service.process(event(PLATE, SQUARE, null));

        verify(violations).resolveOthers(PLATE, ZONE, null, null, ResolutionReason.LEFT);
        verifyNoInteractions(evaluator);
    }

    @Test
    void observationsWithoutAPlateAreIgnored() {
        service.process(event(null, SQUARE, null));
        service.process(event("  ", SQUARE, null));

        verifyNoInteractions(boundary, evaluator, violations);
    }

    @Test
    void zonesWithoutSpotsAreSkipped() {
        when(spotCache.spotsOf(ZONE)).thenReturn(List.of());

        service.process(event(PLATE, SQUARE, null));

        verifyNoInteractions(boundary, evaluator, violations);
    }

    @Test
    void anUnusablePolygonIsDiscarded() {
        service.process(event(PLATE, List.of(List.of(0.0, 0.0), List.of(1.0), List.of(2.0, 2.0)), null));

        verifyNoInteractions(boundary, evaluator, violations);
    }
}
