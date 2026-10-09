package com.parkview.ruleengine.service;

import com.parkview.ruleengine.config.RuleEngineProperties;
import com.parkview.ruleengine.domain.BoundaryCheckResult;
import com.parkview.ruleengine.domain.ParkingSpot;
import com.parkview.ruleengine.domain.ViolationType;
import com.parkview.ruleengine.geometry.BoundingBox;
import com.parkview.ruleengine.geometry.Polygon;
import com.parkview.ruleengine.geometry.PolygonGeometry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Decision layer of the rule engine. Geometry lives in {@link PolygonGeometry}; this class decides
 * which spot a vehicle occupies and which rule, if any, it breaks there.
 *
 * <ol>
 *   <li>Spot selection: bounding-box pre-filter, then the spot with the largest overlap. The vehicle
 *       counts as parked in it only if at least {@code min-overlap} of the vehicle lies inside.</li>
 *   <li>Rules, first match wins: {@code no_parking} spot gives NO_PARKING; a {@code permit} spot gives
 *       WRONG_PERMIT when the plate is known and holds no valid permit of the required type
 *       (an unknown plate cannot be judged); otherwise BOUNDARY_EXCEEDED when the vehicle is not
 *       inside the lines (IoU below {@code iou-threshold} and containment below
 *       {@code containment-threshold}).</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BoundaryService {

    private final RuleEngineProperties properties;
    private final PermitService permitService;

    /**
     * @param plate normalised plate, null when unknown
     * @return the check for the occupied spot, empty when the vehicle is not parked in any of the spots
     */
    public Optional<BoundaryCheckResult> check(Polygon vehicle, List<ParkingSpot> spots, String plate, Instant at) {
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
                .map(o -> evaluate(vehicle, o.spot(), plate, at));
    }

    private BoundaryCheckResult evaluate(Polygon vehicle, ParkingSpot spot, String plate, Instant at) {
        double iou = PolygonGeometry.iou(vehicle, spot.polygon());
        double containment = PolygonGeometry.containment(vehicle, spot.polygon());
        boolean insideLines = iou >= properties.iouThreshold() || containment >= properties.containmentThreshold();
        return new BoundaryCheckResult(spot, iou, containment, violationFor(spot, plate, insideLines, at));
    }

    private ViolationType violationFor(ParkingSpot spot, String plate, boolean insideLines, Instant at) {
        if (spot.isNoParking()) {
            return ViolationType.NO_PARKING;
        }
        if (spot.requiresPermit()) {
            if (plate == null) {
                log.debug("Spot {} requires permit '{}' but the plate is unknown: cannot decide", spot.id(), spot.permitType());
            } else if (!permitService.hasValidPermit(plate, spot.zoneId(), spot.permitType(), at)) {
                return ViolationType.WRONG_PERMIT;
            }
        }
        return insideLines ? null : ViolationType.BOUNDARY_EXCEEDED;
    }

    private record Overlap(ParkingSpot spot, double area) {
    }
}
