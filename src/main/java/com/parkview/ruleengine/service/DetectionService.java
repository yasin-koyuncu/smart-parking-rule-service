package com.parkview.ruleengine.service;

import com.parkview.ruleengine.domain.BoundaryCheckResult;
import com.parkview.ruleengine.domain.ParkingSpot;
import com.parkview.ruleengine.domain.ResolutionReason;
import com.parkview.ruleengine.domain.VehicleClass;
import com.parkview.ruleengine.domain.ViolationType;
import com.parkview.ruleengine.dto.event.VehicleDetectedEvent;
import com.parkview.ruleengine.engine.Finding;
import com.parkview.ruleengine.engine.Observation;
import com.parkview.ruleengine.engine.ViolationEvaluator;
import com.parkview.ruleengine.geometry.Polygon;
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
        List<ParkingSpot> spots = spotCache.spotsOf(event.zoneId());
        if (spots.isEmpty()) {
            log.debug("Zone {} has no polygon spots: nothing to check", event.zoneId());
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
