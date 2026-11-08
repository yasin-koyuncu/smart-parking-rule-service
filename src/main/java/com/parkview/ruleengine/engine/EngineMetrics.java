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

    /**
     * A fine was not issued and the violation stays open: the site has no usable currency ({@code currency} "unknown") or no fine amount is
     * configured for the currency and violation type ({@code cause}: no_site_currency, no_fine_amount). Alert on it.
     */
    public void fineNotIssued(String cause, String currency, ViolationType type) {
        registry.counter("ruleengine.fines.not_issued", "cause", cause, "currency", currency, "type", type.name()).increment();
    }

    /** A completed payment needs a person to look at it ({@code cause}: voided, already_paid, amount_mismatch, unknown_fine). */
    public void paymentNeedsReview(String cause) {
        registry.counter("ruleengine.payments.needs_review", "cause", cause).increment();
    }
}
