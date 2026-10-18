package com.parkview.ruleengine.engine;

import com.parkview.ruleengine.client.ActiveSessionDirectory;
import com.parkview.ruleengine.client.VehicleDirectory;
import com.parkview.ruleengine.config.RuleEngineProperties;
import com.parkview.ruleengine.domain.Fuel;
import com.parkview.ruleengine.domain.ParkingSpot;
import com.parkview.ruleengine.domain.ViolationType;
import com.parkview.ruleengine.domain.ZoneRule;
import com.parkview.ruleengine.service.PermitService;
import com.parkview.ruleengine.service.Plates;
import com.parkview.ruleengine.service.RuleCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The rule engine: decides which violation, if any, an observed vehicle commits. First violation wins:
 *
 * <ol>
 *   <li>{@code NO_PARKING}: the spot type is {@code no_parking} or the deciding zone rule forbids parking.</li>
 *   <li>{@code WRONG_VEHICLE_TYPE}: an {@code ev} spot and a vehicle known not to be electric.</li>
 *   <li>{@code WRONG_PERMIT}: the plate lacks a permit the spot ({@code disabled}, or {@code permit} with its type)
 *       or the deciding zone rule ({@code permit_required}) demands.</li>
 *   <li>{@code OVERSTAY}: the active session of the plate is older than the stay limit (zone rule, else the
 *       {@code timed}/{@code loading} spot's limit, else the zone's).</li>
 *   <li>{@code BOUNDARY_EXCEEDED}: the vehicle is not inside the lines.</li>
 * </ol>
 *
 * <p>Whatever cannot be judged (unknown plate, unknown vehicle, no session, failed lookup) is not a violation
 * (fail open). Registry data, the permit query and the session query are used only when a decision needs them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ViolationEvaluator {

    private final RuleCacheService ruleCache;
    private final RuleResolver ruleResolver;
    private final PermitService permitService;
    private final ActiveSessionDirectory sessions;
    private final VehicleDirectory vehicles;
    private final RuleEngineProperties properties;
    private final EngineMetrics metrics;

    /** The violation the observation commits, if any. */
    public Optional<ViolationType> evaluate(Observation observation) {
        return assess(observation).map(Finding::type);
    }

    /** The same decision, together with the plain-language text of the rule that decided it. */
    public Optional<Finding> assess(Observation observation) {
        metrics.observationEvaluated();
        ParkingSpot spot = observation.spot();
        String zoneId = spot.zoneId();
        List<ZoneRule> rules = ruleCache.rulesOf(zoneId);
        VehicleContext vehicle = new VehicleContext(observation.plate(), observation.detectedClass(),
                observation.zone().country(), vehicles, properties.vehicleLookupEnabled());
        RuleOutcome outcome = ruleResolver.resolve(rules, observation.zone(), observation.at(), vehicle);

        if (spot.isNoParking() || outcome.noParking()) {
            return finding(ViolationType.NO_PARKING, observation, outcome, 0);
        }
        if (spot.isEv() && observation.plate() != null && vehicle.fuel() == Fuel.OTHER) {
            return finding(ViolationType.WRONG_VEHICLE_TYPE, observation, outcome, 0);
        }
        if (lacksRequiredPermit(observation, outcome)) {
            return finding(ViolationType.WRONG_PERMIT, observation, outcome, 0);
        }
        if (overstays(observation, outcome)) {
            return finding(ViolationType.OVERSTAY, observation, outcome, effectiveLimitMinutes(observation, outcome));
        }
        return observation.insideLines() ? Optional.empty()
                : finding(ViolationType.BOUNDARY_EXCEEDED, observation, outcome, 0);
    }

    private static Optional<Finding> finding(ViolationType type, Observation observation, RuleOutcome outcome, int limitMinutes) {
        return Optional.of(new Finding(type, RuleSummaries.of(type, observation, outcome, limitMinutes)));
    }

    /** One permit query for every permit type demanded (spot and rule), compared case-insensitively. */
    private boolean lacksRequiredPermit(Observation observation, RuleOutcome outcome) {
        Set<String> required = new LinkedHashSet<>();
        addRequired(required, observation.spot().requiredPermitType());
        addRequired(required, outcome.permitRequired());
        if (required.isEmpty()) {
            return false;
        }
        if (observation.plate() == null) {
            log.debug("Spot {} requires permit {} but the plate is unknown: cannot decide", observation.spot().id(), required);
            return false;
        }
        return !permitService.holdsAll(observation.plate(), observation.spot().zoneId(), required, observation.at());
    }

    private static void addRequired(Set<String> required, String permitType) {
        if (permitType != null && !permitType.isBlank()) {
            required.add(permitType.trim().toLowerCase(Locale.ROOT));
        }
    }

    private boolean overstays(Observation observation, RuleOutcome outcome) {
        int limit = effectiveLimitMinutes(observation, outcome);
        if (limit <= 0) {
            return false;
        }
        if (observation.plate() == null) {
            log.debug("Spot {} has a stay limit but the plate is unknown: cannot decide", observation.spot().id());
            return false;
        }
        Optional<Instant> started = sessions.startOfActiveSession(observation.plate(), observation.spot().zoneId());
        if (started.isEmpty()) {
            log.debug("No active session for {} in zone {}: cannot decide overstay",
                    Plates.mask(observation.plate()), observation.spot().zoneId());
            return false;
        }
        return Duration.between(started.get(), observation.at()).compareTo(Duration.ofMinutes(limit)) > 0;
    }

    /** Deciding rule's limit, else the spot's own (timed and loading spots), else the zone's; zero means unlimited. */
    private static int effectiveLimitMinutes(Observation observation, RuleOutcome outcome) {
        if (outcome.maxMinutes() > 0) {
            return outcome.maxMinutes();
        }
        if (observation.spot().ownMaxMinutes() > 0) {
            return observation.spot().ownMaxMinutes();
        }
        return observation.zone().maxMinutes();
    }
}
