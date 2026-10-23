package com.parkview.ruleengine.service.violation;

import com.parkview.ruleengine.domain.type.ResolutionReason;
import com.parkview.ruleengine.domain.type.VehicleClass;
import com.parkview.ruleengine.domain.type.ViolationType;
import com.parkview.ruleengine.domain.value.BoundaryCheckResult;
import com.parkview.ruleengine.domain.value.ParkingSpot;
import com.parkview.ruleengine.dto.event.VehicleDetectedEvent;
import com.parkview.ruleengine.engine.Finding;
import com.parkview.ruleengine.engine.Observation;
import com.parkview.ruleengine.engine.ViolationEvaluator;
import com.parkview.ruleengine.geometry.Polygon;
import com.parkview.ruleengine.service.cache.RuleCacheService;
import com.parkview.ruleengine.service.cache.SpotCacheService;
import com.parkview.ruleengine.util.Plates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Turns one {@code vehicle.detected} observation into violation state: the vehicle is matched to the single
 * spot it occupies, the rule engine decides what (if anything) it breaks there, and the open violations of the
 * plate are confirmed, opened or resolved accordingly.
 *
 * <p>An observation carrying a {@code viewId} is matched against the spots of that camera view, one without against the
 * legacy spots of the zone (see {@link SpotCacheService}).
 *
 * <p>An observation without a plate cannot be attributed to anyone and is ignored.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DetectionService {

    private final SpotCacheService spotCache;
    private final RuleCacheService ruleCache;
    private final BoundaryService boundaryService;
    private final ViolationEvaluator evaluator;
    private final ViolationService violationService;
    private final Clock clock;

    public void process(VehicleDetectedEvent event) {
        String plate = Plates.normalize(event.plate());
        if (plate == null) {
            log.debug("Observation in zone {} without a plate: ignored", event.zoneId());
            return;
        }
        // An observation made on a camera view is matched against the spots of that view (their image polygons), a legacy one
        // against the zone's spots that are on no view; the two kinds are never mixed.
        List<ParkingSpot> spots = spotCache.spotsOf(event.zoneId(), event.viewId());
        if (spots.isEmpty()) {
            log.debug("Zone {} view {} has no polygon spots: nothing to check", event.zoneId(), event.viewId());
            return;
        }
        Polygon vehicle;
        try {
            vehicle = Polygon.of(event.vehiclePolygon());
        } catch (IllegalArgumentException e) {
            log.warn("Observation from camera {} has an unusable vehicle polygon: {}", event.cameraId(), e.getMessage());
            return;
        }

        Optional<BoundaryCheckResult> fit = boundaryService.check(vehicle, spots);
        if (fit.isEmpty()) {
            violationService.resolveOthers(plate, event.zoneId(), null, null, ResolutionReason.LEFT);
            return;
        }
        ParkingSpot spot = fit.get().spot();
        Observation observation = new Observation(ruleCache.zoneOf(event.zoneId()), spot, fit.get().insideLines(), plate,
                VehicleClass.fromDetector(event.vehicleClass()).orElse(null), clock.instant());

        Optional<Finding> finding = evaluator.assess(observation);
        if (finding.isEmpty()) {
            violationService.resolveOthers(plate, event.zoneId(), null, null, ResolutionReason.CORRECTED);
        } else {
            ViolationType type = finding.get().type();
            violationService.confirm(spot, type, plate, event.cameraId(), finding.get().ruleSummary());
            violationService.resolveOthers(plate, event.zoneId(), spot.id(), type, ResolutionReason.CORRECTED);
        }
    }
}
