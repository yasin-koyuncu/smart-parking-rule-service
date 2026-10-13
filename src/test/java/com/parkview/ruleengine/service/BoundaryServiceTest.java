package com.parkview.ruleengine.service;

import com.parkview.ruleengine.domain.BoundaryCheckResult;
import com.parkview.ruleengine.domain.ParkingSpot;
import com.parkview.ruleengine.geometry.Point;
import com.parkview.ruleengine.geometry.Polygon;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BoundaryServiceTest {

    private static final String ZONE = "zone-1";

    private final BoundaryService service = new BoundaryService(TestFixtures.properties());

    private static Polygon rect(double x0, double y0, double x1, double y1) {
        return new Polygon(List.of(new Point(x0, y0), new Point(x1, y0), new Point(x1, y1), new Point(x0, y1)));
    }

    private static Polygon clockwise(Polygon p) {
        List<Point> reversed = new ArrayList<>(p.vertices());
        Collections.reverse(reversed);
        return new Polygon(reversed);
    }

    private static ParkingSpot spot(Polygon polygon) {
        return new ParkingSpot(UUID.randomUUID(), ZONE, "A1", "paid", null, polygon, 0, 0, 0);
    }

    @Test
    void vehicleExactlyInASpotIsInsideTheLines() {
        ParkingSpot spot = spot(rect(0, 0, 10, 10));

        Optional<BoundaryCheckResult> result = service.check(rect(0, 0, 10, 10), List.of(spot));

        assertThat(result).isPresent();
        assertThat(result.get().insideLines()).isTrue();
        assertThat(result.get().iou()).isEqualTo(1.0);
        assertThat(result.get().spot()).isEqualTo(spot);
    }

    @Test
    void smallVehicleInsideALargeSpotIsInsideTheLines() {
        ParkingSpot spot = spot(rect(0, 0, 10, 10));

        BoundaryCheckResult result = service.check(rect(2, 2, 6, 6), List.of(spot)).orElseThrow();

        assertThat(result.iou()).isLessThan(0.85);
        assertThat(result.containment()).isEqualTo(1.0);
        assertThat(result.insideLines()).isTrue();
    }

    @Test
    void windingOfTheSpotDoesNotChangeTheDecision() {
        Polygon vehicle = rect(5, 0, 15, 10);

        BoundaryCheckResult ccw = service.check(vehicle, List.of(spot(rect(0, 0, 10, 10)))).orElseThrow();
        BoundaryCheckResult cw = service.check(vehicle, List.of(spot(clockwise(rect(0, 0, 10, 10))))).orElseThrow();

        assertThat(ccw.insideLines()).isFalse();
        assertThat(cw.insideLines()).isFalse();
        assertThat(cw.iou()).isEqualTo(ccw.iou());
        assertThat(cw.containment()).isEqualTo(ccw.containment());
    }

    @Test
    void vehiclePartlyOutsideTheLinesIsParkedInTheSpotButNotInsideTheLines() {
        ParkingSpot spot = spot(rect(0, 0, 10, 10));

        // 70% of the vehicle is inside: parked in this spot, but not inside the lines
        BoundaryCheckResult result = service.check(rect(3, 0, 13, 10), List.of(spot)).orElseThrow();

        assertThat(result.containment()).isEqualTo(0.7, within(1e-9));
        assertThat(result.insideLines()).isFalse();
    }

    @Test
    void vehicleMostlyOutsideIsNotParkedInTheSpot() {
        assertThat(service.check(rect(7, 0, 17, 10), List.of(spot(rect(0, 0, 10, 10))))).isEmpty();
    }

    @Test
    void vehicleOutsideAllSpotsIsNotParked() {
        assertThat(service.check(rect(50, 50, 60, 60), List.of(spot(rect(0, 0, 10, 10))))).isEmpty();
        assertThat(service.check(rect(0, 0, 1, 1), List.of())).isEmpty();
    }

    @Test
    void theSpotWithTheLargestOverlapIsChosen() {
        ParkingSpot left = spot(rect(0, 0, 10, 10));
        ParkingSpot right = spot(rect(10, 0, 20, 10));

        // 6 units in the right spot, 4 in the left one
        BoundaryCheckResult result = service.check(rect(6, 0, 16, 10), List.of(left, right)).orElseThrow();

        assertThat(result.spot()).isEqualTo(right);
    }

    @Test
    void degenerateVehiclePolygonIsNotParked() {
        Polygon line = new Polygon(List.of(new Point(0, 0), new Point(5, 5), new Point(10, 10)));

        assertThat(service.check(line, List.of(spot(rect(0, 0, 10, 10))))).isEmpty();
    }
}
