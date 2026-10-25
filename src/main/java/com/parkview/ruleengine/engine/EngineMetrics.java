package com.parkview.ruleengine.engine;

import com.parkview.ruleengine.domain.type.ViolationType;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Micrometer counters of the rule engine (exposed on {@code /actuator/prometheus}). */
@Component
public class EngineMetrics {

    private final MeterRegistry registry;

    public EngineMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** One observation went through the engine. */
    public void observationEvaluated() {
        registry.counter("ruleengine.observations.evaluated").increment();
    }

    /** A new violation was opened. */
    public void violationCreated(ViolationType type) {
        registry.counter("ruleengine.violations.created", "type", type.name()).increment();
    }

    /** A vehicle registry lookup failed (timeout, error status, unreachable). */
    public void lookupFailed() {
        registry.counter("ruleengine.vehicle.lookups.failed").increment();
    }

    /** A zone rule decided an outcome category ({@code no_parking}, {@code permit}, {@code max_minutes}). */
    public void ruleMatched(String outcome) {
        registry.counter("ruleengine.rules.matched", "outcome", outcome).increment();
    }

    /** A completed payment needs a person to look at it ({@code cause}: voided, already_paid, amount_mismatch, unknown_fine). */
    public void paymentNeedsReview(String cause) {
        registry.counter("ruleengine.payments.needs_review", "cause", cause).increment();
    }
}
