package com.parkview.ruleengine.service;

import com.parkview.ruleengine.config.RuleEngineProperties;
import com.parkview.ruleengine.domain.BoundaryCheckResult;
import com.parkview.ruleengine.domain.ParkingSpot;
import com.parkview.ruleengine.geometry.BoundingBox;
import com.parkview.ruleengine.geometry.Polygon;
import com.parkview.ruleengine.geometry.PolygonGeometry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Geometry and spot selection. Geometry lives in {@link PolygonGeometry}; this class decides which spot a
 * vehicle occupies and whether it sits inside the lines. Which rule that breaks is decided by the rule engine
 * ({@code ViolationEvaluator}).
 *
 * <p>Spot selection: bounding-box pre-filter, then the spot with the largest overlap. The vehicle counts as
 * parked in it only if at least {@code min-overlap} of the vehicle lies inside. Inside the lines means IoU at
 * least {@code iou-threshold} or containment at least {@code containment-threshold}.
 */
@Service
@RequiredArgsConstructor
public class BoundaryService {

    private final RuleEngineProperties properties;

    /** @return the occupied spot and the fit, empty when the vehicle is not parked in any of the spots */
    public Optional<BoundaryCheckResult> check(Polygon vehicle, List<ParkingSpot> spots) {
        double vehicleArea = PolygonGeometry.area(vehicle);
        if (vehicleArea <= 0) {
            return Optional.empty();
        }
        BoundingBox vehicleBox = PolygonGeometry.boundingBox(vehicle);

        return spots.stream()
                .filter(spot -> PolygonGeometry.boundingBox(spot.polygon()).overlaps(vehicleBox))
                .map(spot -> new Overlap(spot, PolygonGeometry.intersectionArea(vehicle, spot.polygon())))
                .filter(o -> o.area() > 0)
                .max(Comparator.comparingDouble(Overlap::area).thenComparing(o -> o.spot().id()))
                .filter(o -> o.area() / vehicleArea >= properties.minOverlap())
                .map(o -> fit(vehicle, o.spot()));
    }

    private BoundaryCheckResult fit(Polygon vehicle, ParkingSpot spot) {
        double iou = PolygonGeometry.iou(vehicle, spot.polygon());
        double containment = PolygonGeometry.containment(vehicle, spot.polygon());
        boolean insideLines = iou >= properties.iouThreshold() || containment >= properties.containmentThreshold();
        return new BoundaryCheckResult(spot, iou, containment, insideLines);
    }

    private record Overlap(ParkingSpot spot, double area) {
    }
}
