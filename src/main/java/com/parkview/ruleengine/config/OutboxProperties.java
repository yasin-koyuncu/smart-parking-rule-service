package com.parkview.ruleengine.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Transactional outbox relay ({@code parkview.outbox.*}).
 *
 * @param relayEnabled   switch the scheduled relay and clean-up off (tests, maintenance)
 * @param relayInterval  delay between two relay runs; a run sends the oldest unpublished event of each fine or violation that is due,
 *                       so this is also the delay between two events of one fine
 * @param batchSize      rows claimed per run
 * @param confirmTimeout how long to wait for the broker's publisher confirm of one message
 * @param maxBackoff     upper bound of the exponential retry delay of a failing row
 * @param retention      how long published rows are kept
 * @param unroutableRetryWindow how long a message the broker confirms but cannot route (no queue bound, e.g. the consumer has not declared its
 *                       queue yet after a start) is retried like a failure; a row older than this is logged, counted and parked (marked published),
 *                       so an event nobody consumes does not retry forever
 */
@Validated
@ConfigurationProperties("parkview.outbox")
public record OutboxProperties(
        @DefaultValue("true") boolean relayEnabled,
        @NotNull @DefaultValue("250ms") Duration relayInterval,
        @Min(1) @Max(1000) @DefaultValue("50") int batchSize,
        @NotNull @DefaultValue("5s") Duration confirmTimeout,
        @NotNull @DefaultValue("60s") Duration maxBackoff,
        @NotNull @DefaultValue("14d") Duration retention,
        @NotNull @DefaultValue("PT5M") Duration unroutableRetryWindow
) {
}
