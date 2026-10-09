package com.parkview.ruleengine.service;

import com.parkview.ruleengine.domain.BoundaryCheckResult;
import com.parkview.ruleengine.domain.ParkingSpot;
import com.parkview.ruleengine.domain.ResolutionReason;
import com.parkview.ruleengine.domain.ViolationType;
import com.parkview.ruleengine.dto.event.VehicleDetectedEvent;
import com.parkview.ruleengine.geometry.Polygon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    private final BoundaryService boundary = mock(BoundaryService.class);
    private final ViolationService violations = mock(ViolationService.class);
    private final ParkingSpot spot = TestFixtures.spot("permit", "resident", 0, 0);
    private DetectionService service;

    @BeforeEach
    void setUp() {
        service = new DetectionService(spotCache, boundary, violations, Clock.fixed(NOW, ZoneOffset.UTC));
        when(spotCache.spotsOf(ZONE)).thenReturn(List.of(spot));
    }

    private static VehicleDetectedEvent event(String plate, List<List<Double>> polygon) {
        return new VehicleDetectedEvent("cam-1", ZONE, plate, polygon, null, null, 0.9, 0.9, NOW.toEpochMilli());
    }

    @Test
    void aViolationIsConfirmedAndEverythingElseOfThePlateIsCorrected() {
        when(boundary.check(any(Polygon.class), eq(List.of(spot)), eq(PLATE), eq(NOW)))
                .thenReturn(Optional.of(new BoundaryCheckResult(spot, 1.0, 1.0, ViolationType.WRONG_PERMIT)));

        service.process(event("abc 123", SQUARE));

        verify(violations).confirm(spot, ViolationType.WRONG_PERMIT, PLATE, "cam-1");
        verify(violations).resolveOthers(PLATE, ZONE, spot.id(), ViolationType.WRONG_PERMIT, ResolutionReason.CORRECTED);
    }

    @Test
    void validParkingResolvesOpenViolationsAsCorrected() {
        when(boundary.check(any(Polygon.class), any(), eq(PLATE), any()))
                .thenReturn(Optional.of(new BoundaryCheckResult(spot, 1.0, 1.0, null)));

        service.process(event(PLATE, SQUARE));

        verify(violations, never()).confirm(any(), any(), anyString(), anyString());
        verify(violations).resolveOthers(PLATE, ZONE, null, null, ResolutionReason.CORRECTED);
    }

    @Test
    void aVehicleOutsideAllSpotsResolvesOpenViolationsAsLeft() {
        when(boundary.check(any(Polygon.class), any(), eq(PLATE), any())).thenReturn(Optional.empty());

        service.process(event(PLATE, SQUARE));

        verify(violations).resolveOthers(PLATE, ZONE, null, null, ResolutionReason.LEFT);
    }

    @Test
    void detectionsWithoutAPlateAreIgnored() {
        service.process(event(null, SQUARE));
        service.process(event("  ", SQUARE));

        verifyNoInteractions(boundary, violations);
    }

    @Test
    void zonesWithoutSpotsAreSkipped() {
        when(spotCache.spotsOf(ZONE)).thenReturn(List.of());

        service.process(event(PLATE, SQUARE));

        verifyNoInteractions(boundary, violations);
    }

    @Test
    void anUnusablePolygonIsDiscarded() {
        service.process(event(PLATE, List.of(List.of(0.0, 0.0), List.of(1.0), List.of(2.0, 2.0))));

        verifyNoInteractions(boundary, violations);
    }
}
