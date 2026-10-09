package com.parkview.ruleengine.service;

import com.parkview.ruleengine.config.RuleEngineProperties;
import com.parkview.ruleengine.domain.BoundaryCheckResult;
import com.parkview.ruleengine.domain.ParkingSpot;
import com.parkview.ruleengine.domain.ViolationType;
import com.parkview.ruleengine.geometry.Point;
import com.parkview.ruleengine.geometry.Polygon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BoundaryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");
    private static final String ZONE = "zone-1";
    private static final String PLATE = "ABC123";

    private final PermitService permits = mock(PermitService.class);
    private BoundaryService service;

    @BeforeEach
    void setUp() {
        RuleEngineProperties props = new RuleEngineProperties(0.85, 0.90, 0.5, 10, 30,
                BigDecimal.valueOf(900), BigDecimal.valueOf(450), BigDecimal.valueOf(700), BigDecimal.valueOf(900),
                60000, 20, 15, 60000, 30, 5);
        service = new BoundaryService(props, permits);
    }

    private static Polygon rect(double x0, double y0, double x1, double y1) {
        return new Polygon(List.of(new Point(x0, y0), new Point(x1, y0), new Point(x1, y1), new Point(x0, y1)));
    }

    private static Polygon clockwise(Polygon p) {
        List<Point> reversed = new ArrayList<>(p.vertices());
        Collections.reverse(reversed);
        return new Polygon(reversed);
    }

    private static ParkingSpot spot(String type, String permitType, Polygon polygon) {
        return new ParkingSpot(UUID.randomUUID(), ZONE, "A1", type, permitType, polygon, 0, 0);
    }

    @Test
    void vehicleExactlyInAPaidSpotIsValid() {
        ParkingSpot spot = spot("paid", null, rect(0, 0, 10, 10));

        Optional<BoundaryCheckResult> result = service.check(rect(0, 0, 10, 10), List.of(spot), PLATE, NOW);

        assertThat(result).isPresent();
        assertThat(result.get().violation()).isNull();
        assertThat(result.get().iou()).isEqualTo(1.0);
        assertThat(result.get().spot()).isEqualTo(spot);
    }

    @Test
    void smallVehicleInsideALargeSpotIsValid() {
        ParkingSpot spot = spot("paid", null, rect(0, 0, 10, 10));

        BoundaryCheckResult result = service.check(rect(2, 2, 6, 6), List.of(spot), PLATE, NOW).orElseThrow();

        assertThat(result.iou()).isLessThan(0.85);
        assertThat(result.containment()).isEqualTo(1.0);
        assertThat(result.violation()).isNull();
    }

    @Test
    void windingOfTheSpotDoesNotChangeTheDecision() {
        Polygon vehicle = rect(5, 0, 15, 10);
        ParkingSpot ccwSpot = spot("paid", null, rect(0, 0, 10, 10));
        ParkingSpot cwSpot = spot("paid", null, clockwise(rect(0, 0, 10, 10)));

        BoundaryCheckResult ccw = service.check(vehicle, List.of(ccwSpot), PLATE, NOW).orElseThrow();
        BoundaryCheckResult cw = service.check(vehicle, List.of(cwSpot), PLATE, NOW).orElseThrow();

        assertThat(ccw.violation()).isEqualTo(ViolationType.BOUNDARY_EXCEEDED);
        assertThat(cw.violation()).isEqualTo(ViolationType.BOUNDARY_EXCEEDED);
        assertThat(cw.iou()).isEqualTo(ccw.iou());
        assertThat(cw.containment()).isEqualTo(ccw.containment());
    }

    @Test
    void vehiclePartlyOutsideTheLinesExceedsTheBoundary() {
        ParkingSpot spot = spot("paid", null, rect(0, 0, 10, 10));

        // 70% of the vehicle is inside: parked in this spot, but not inside the lines
        BoundaryCheckResult result = service.check(rect(3, 0, 13, 10), List.of(spot), PLATE, NOW).orElseThrow();

        assertThat(result.containment()).isEqualTo(0.7, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(result.violation()).isEqualTo(ViolationType.BOUNDARY_EXCEEDED);
    }

    @Test
    void vehicleMostlyOutsideIsNotParkedInTheSpot() {
        ParkingSpot spot = spot("paid", null, rect(0, 0, 10, 10));

        assertThat(service.check(rect(7, 0, 17, 10), List.of(spot), PLATE, NOW)).isEmpty();
    }

    @Test
    void vehicleOutsideAllSpotsIsNotParked() {
        assertThat(service.check(rect(50, 50, 60, 60), List.of(spot("paid", null, rect(0, 0, 10, 10))), PLATE, NOW)).isEmpty();
        assertThat(service.check(rect(0, 0, 1, 1), List.of(), PLATE, NOW)).isEmpty();
    }

    @Test
    void theSpotWithTheLargestOverlapIsChosen() {
        ParkingSpot left = spot("paid", null, rect(0, 0, 10, 10));
        ParkingSpot right = spot("paid", null, rect(10, 0, 20, 10));

        // 6 units in the right spot, 4 in the left one
        BoundaryCheckResult result = service.check(rect(6, 0, 16, 10), List.of(left, right), PLATE, NOW).orElseThrow();

        assertThat(result.spot()).isEqualTo(right);
    }

    @Test
    void onlyTheBestSpotIsEvaluatedSoNeighboursDoNotAddViolations() {
        ParkingSpot noParkingNeighbour = spot("no_parking", null, rect(10, 0, 20, 10));
        ParkingSpot ownSpot = spot("paid", null, rect(0, 0, 10, 10));

        BoundaryCheckResult result = service
                .check(rect(1, 0, 11, 10), List.of(noParkingNeighbour, ownSpot), PLATE, NOW).orElseThrow();

        assertThat(result.spot()).isEqualTo(ownSpot);
        assertThat(result.violation()).isNull();
    }

    @Test
    void parkingInANoParkingSpotIsAViolation() {
        ParkingSpot spot = spot("no_parking", null, rect(0, 0, 10, 10));

        assertThat(service.check(rect(0, 0, 10, 10), List.of(spot), PLATE, NOW).orElseThrow().violation())
                .isEqualTo(ViolationType.NO_PARKING);
    }

    @Test
    void knownPlateWithoutPermitInAPermitSpotIsWrongPermit() {
        ParkingSpot spot = spot("permit", "resident", rect(0, 0, 10, 10));
        when(permits.hasValidPermit(PLATE, ZONE, "resident", NOW)).thenReturn(false);

        assertThat(service.check(rect(0, 0, 10, 10), List.of(spot), PLATE, NOW).orElseThrow().violation())
                .isEqualTo(ViolationType.WRONG_PERMIT);
    }

    @Test
    void knownPlateWithAValidPermitIsAccepted() {
        ParkingSpot spot = spot("permit", "resident", rect(0, 0, 10, 10));
        when(permits.hasValidPermit(PLATE, ZONE, "resident", NOW)).thenReturn(true);

        assertThat(service.check(rect(0, 0, 10, 10), List.of(spot), PLATE, NOW).orElseThrow().violation()).isNull();
    }

    @Test
    void permitHolderStillGetsABoundaryViolationWhenPartlyOutside() {
        ParkingSpot spot = spot("permit", "resident", rect(0, 0, 10, 10));
        when(permits.hasValidPermit(PLATE, ZONE, "resident", NOW)).thenReturn(true);

        assertThat(service.check(rect(3, 0, 13, 10), List.of(spot), PLATE, NOW).orElseThrow().violation())
                .isEqualTo(ViolationType.BOUNDARY_EXCEEDED);
    }

    @Test
    void unknownPlateCannotBeJudgedAgainstPermits() {
        ParkingSpot spot = spot("permit", "resident", rect(0, 0, 10, 10));

        BoundaryCheckResult result = service.check(rect(0, 0, 10, 10), List.of(spot), null, NOW).orElseThrow();

        assertThat(result.violation()).isNull();
        verify(permits, never()).hasValidPermit(anyString(), anyString(), anyString(), any());
    }

    @Test
    void permitSpotWithoutRequiredTypeNeedsNoPermit() {
        ParkingSpot spot = spot("permit", null, rect(0, 0, 10, 10));

        assertThat(service.check(rect(0, 0, 10, 10), List.of(spot), PLATE, NOW).orElseThrow().violation()).isNull();
        verify(permits, never()).hasValidPermit(anyString(), anyString(), anyString(), any());
    }

    @Test
    void permitIsCheckedForTheSpotsZoneAndTypeAtTheDetectionTime() {
        ParkingSpot spot = spot("permit", "visitor", rect(0, 0, 10, 10));

        service.check(rect(0, 0, 10, 10), List.of(spot), PLATE, NOW);

        verify(permits).hasValidPermit(eq(PLATE), eq(ZONE), eq("visitor"), eq(NOW));
    }

    @Test
    void degenerateVehiclePolygonIsNotParked() {
        Polygon line = new Polygon(List.of(new Point(0, 0), new Point(5, 5), new Point(10, 10)));

        assertThat(service.check(line, List.of(spot("paid", null, rect(0, 0, 10, 10))), PLATE, NOW)).isEmpty();
    }
}
