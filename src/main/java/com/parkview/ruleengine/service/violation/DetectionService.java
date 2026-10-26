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
import com.parkview.ruleengine.config.RuleEngineProperties;
import com.parkview.ruleengine.service.cache.RuleCacheService;
import com.parkview.ruleengine.service.cache.SpotCacheService;
import com.parkview.ruleengine.util.Plates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Turns one {@code vehicle.detected} observation into violation state: the vehicle is matched to the single
 * spot it occupies, the rule engine decides what (if anything) it breaks there, and the open violations of the
 * plate are confirmed, opened or resolved accordingly.
 *
 * <p>An observation carrying a {@code viewId} is matched against the spots of that camera view, one without against the
 * legacy spots of the site (see {@link SpotCacheService}). The spots are those of the site, in a zone or not (ADR 0009); an
 * observation from a producer that predates sites names only the zone, and the zone's site is used. The parameters and
 * rules that apply are those of the spot found: its zone's, or its site's when it has no zone.
 *
 * <p>An observation without a plate cannot be attributed to anyone and is ignored. The time of an observation is the time
 * of the event (clamped to now), not the time it is processed: after an outage or a backlog an old sighting must neither
 * refresh the {@code last_seen_at} of a violation nor start a fresh grace period. One older than
 * {@code observation-max-age-seconds} is dropped.
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
    private final RuleEngineProperties properties;
    private final Clock clock;

    public void process(VehicleDetectedEvent event) {
        Instant now = clock.instant();
        Instant observedAt = observedAt(event, now);
        Duration age = Duration.between(observedAt, now);
        String zoneId = blankToNull(event.zoneId());
        if (age.compareTo(properties.observationMaxAge()) > 0) {
            // no plate in the log: it is personal data
            log.debug("Observation at site {} zone {} from camera {} is {} s old: dropped", event.siteId(), zoneId, event.cameraId(),
                    age.toSeconds());
            return;
        }
        String siteId = blankToNull(event.siteId()) != null ? event.siteId().trim() : ruleCache.siteIdOf(zoneId);
        if (siteId == null) {
            log.warn("Observation from camera {} names neither a site nor a zone: ignored", event.cameraId());
            return;
        }
        String plate = Plates.normalize(event.plate());
        if (plate == null) {
            log.debug("Observation at site {} without a plate: ignored", siteId);
            return;
        }
        // An observation made on a camera view is matched against the spots of that view (their image polygons), a legacy one
        // against the site's spots that are on no view; the two kinds are never mixed.
        List<ParkingSpot> spots = spotCache.spotsOf(siteId, event.viewId());
        if (spots.isEmpty()) {
            log.debug("Site {} view {} has no polygon spots: nothing to check", siteId, event.viewId());
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
            violationService.resolveOthers(plate, siteId, zoneId, null, null, ResolutionReason.LEFT);
            return;
        }
        ParkingSpot spot = fit.get().spot();
        Observation observation = new Observation(ruleCache.placeOf(spot.siteId(), spot.zoneId()), spot, fit.get().insideLines(), plate,
                VehicleClass.fromDetector(event.vehicleClass()).orElse(null), observedAt);

        Optional<Finding> finding = evaluator.assess(observation);
        if (finding.isEmpty()) {
            violationService.resolveOthers(plate, siteId, zoneId, null, null, ResolutionReason.CORRECTED);
        } else {
            ViolationType type = finding.get().type();
            violationService.confirm(spot, type, plate, event.cameraId(), finding.get().ruleSummary(), observedAt);
            violationService.resolveOthers(plate, siteId, zoneId, spot.id(), type, ResolutionReason.CORRECTED);
        }
    }

    private static String blankToNull(String id) {
        return id == null || id.isBlank() ? null : id.trim();
    }

    /** The event time, never later than {@code now} (a camera clock that runs ahead); an event without a time counts as seen now. */
    private static Instant observedAt(VehicleDetectedEvent event, Instant now) {
        if (event.timestamp() <= 0) {
            return now;
        }
        Instant eventTime = Instant.ofEpochMilli(event.timestamp());
        return eventTime.isAfter(now) ? now : eventTime;
    }
}
