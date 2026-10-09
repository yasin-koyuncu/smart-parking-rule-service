package com.parkview.ruleengine.service;

import com.parkview.ruleengine.domain.BoundaryCheckResult;
import com.parkview.ruleengine.domain.ParkingSpot;
import com.parkview.ruleengine.domain.ResolutionReason;
import com.parkview.ruleengine.dto.event.VehicleDetectedEvent;
import com.parkview.ruleengine.geometry.Polygon;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Turns one {@code vehicle.detected} observation into violation state: the vehicle is matched to
 * the single spot it occupies, that spot is evaluated, and the open violations of the plate are
 * confirmed, opened or resolved accordingly.
 *
 * <p>A detection without a plate cannot be attributed to anyone and is ignored.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DetectionService {

    private final SpotCacheService spotCache;
    private final BoundaryService boundaryService;
    private final ViolationService violationService;
    private final Clock clock;

    public void process(VehicleDetectedEvent event) {
        String plate = Plates.normalize(event.plate());
        if (plate == null) {
            log.debug("Detection in zone {} without a plate: ignored", event.zoneId());
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
            log.warn("Detection from camera {} has an unusable vehicle polygon: {}", event.cameraId(), e.getMessage());
            return;
        }

        Optional<BoundaryCheckResult> result = boundaryService.check(vehicle, spots, plate, clock.instant());
        if (result.isEmpty()) {
            violationService.resolveOthers(plate, event.zoneId(), null, null, ResolutionReason.LEFT);
        } else if (!result.get().isViolation()) {
            violationService.resolveOthers(plate, event.zoneId(), null, null, ResolutionReason.CORRECTED);
        } else {
            BoundaryCheckResult check = result.get();
            violationService.confirm(check.spot(), check.violation(), plate, event.cameraId());
            violationService.resolveOthers(plate, event.zoneId(), check.spot().id(), check.violation(),
                    ResolutionReason.CORRECTED);
        }
    }
}
