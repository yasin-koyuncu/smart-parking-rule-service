package com.parkview.ruleengine.service.outbox;

import com.parkview.ruleengine.config.OutboxProperties;
import com.parkview.ruleengine.domain.OutboxEvent;
import com.parkview.ruleengine.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes outbox rows to RabbitMQ.
 *
 * <p>Each run claims a batch with {@code FOR UPDATE SKIP LOCKED} (safe with several instances), sends every
 * message with a {@link CorrelationData} and then waits for the publisher confirms of the broker. A row is only
 * marked published after an ack; a nack, a missing confirm or an unreachable broker leaves it in the outbox with an
 * exponential back-off (1 s, 2 s, 4 s ... up to {@code max-backoff}). Rows are never given up: a broker outage of
 * any length delays the events, it does not lose them. Delivery is therefore at least once, with the outbox row id
 * as {@code messageId} so consumers can de-duplicate.
 *
 * <p><b>Ordering:</b> the claim query only offers the oldest unpublished row of each fine or violation, so the events
 * of one fine are published one after the other in the order they were written, and a failing row holds back only its
 * own aggregate (see {@link OutboxEventRepository#lockDueBatch}). There is no order between different aggregates.
 *
 * <p>A message the broker acknowledges but cannot route (no queue bound) is, by default, counted and treated as
 * delivered: nobody is listening yet, which is a normal state (the direct publish it replaces dropped it too). With
 * {@code parkview.outbox.retry-unroutable=true} it is retried like a failure.
 */
@Slf4j
@Component
public class OutboxRelay {

    private final OutboxEventRepository outbox;
    private final RabbitTemplate rabbitTemplate;
    private final OutboxProperties properties;
    private final Clock clock;
    private final Counter published;
    private final Counter failed;
    private final Counter unroutable;

    public OutboxRelay(OutboxEventRepository outbox, RabbitTemplate rabbitTemplate, OutboxProperties properties,
                       Clock clock, MeterRegistry meters) {
        this.outbox = outbox;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.clock = clock;
        this.published = Counter.builder("rule.outbox.published").register(meters);
        this.failed = Counter.builder("rule.outbox.failed").register(meters);
        this.unroutable = Counter.builder("rule.outbox.unroutable").register(meters);
        Gauge.builder("rule.outbox.pending", outbox, OutboxEventRepository::countByPublishedAtIsNull)
                .register(meters);
        Gauge.builder("rule.outbox.oldest.pending.seconds", this, OutboxRelay::oldestPendingSeconds)
                .baseUnit("seconds").register(meters);
    }

    /** @return number of messages that were published (confirmed) in this run */
    @Scheduled(fixedDelayString = "${parkview.outbox.relay-interval:250ms}",
            initialDelayString = "${parkview.outbox.relay-interval:250ms}")
    @Transactional
    public int relayDueEvents() {
        if (!properties.relayEnabled()) {
            return 0;
        }
        Instant now = clock.instant();
        List<OutboxEvent> due = outbox.lockDueBatch(now, properties.batchSize());
        if (due.isEmpty()) {
            return 0;
        }

        Map<OutboxEvent, CorrelationData> inFlight = sendAll(due, now);
        return confirmAll(inFlight, now);
    }

    /** Sends the batch in order; stops at the first broker error, the remaining rows stay due. */
    private Map<OutboxEvent, CorrelationData> sendAll(List<OutboxEvent> due, Instant now) {
        Map<OutboxEvent, CorrelationData> inFlight = new LinkedHashMap<>();
        for (OutboxEvent event : due) {
            try {
                CorrelationData correlation = new CorrelationData(event.getId().toString());
                rabbitTemplate.send(event.getExchange(), event.getRoutingKey(), OutboxMessages.toMessage(event), correlation);
                inFlight.put(event, correlation);
            } catch (AmqpException e) {
                // Broker unreachable: the remaining rows stay due and are retried on the next run.
                fail(event, now, "send failed: " + e.getMessage());
                break;
            }
        }
        return inFlight;
    }

    /** Waits for the publisher confirms; confirmed rows are marked published, the others back off. */
    private int confirmAll(Map<OutboxEvent, CorrelationData> inFlight, Instant now) {
        int sent = 0;
        long deadline = System.nanoTime() + properties.confirmTimeout().toNanos();
        for (Map.Entry<OutboxEvent, CorrelationData> entry : inFlight.entrySet()) {
            OutboxEvent event = entry.getKey();
            PublisherConfirms.Outcome outcome = PublisherConfirms.await(entry.getValue(), deadline);
            switch (outcome.status()) {
                case ROUTED -> {
                    markPublished(event, now);
                    sent++;
                }
                case UNROUTABLE -> {
                    if (properties.retryUnroutable()) {
                        fail(event, now, outcome.detail());
                    } else {
                        unroutable.increment();
                        log.warn("Outbox event {} ({}) has no consumer queue bound ({}), treated as delivered",
                                event.getId(), event.getRoutingKey(), outcome.detail());
                        markPublished(event, now);
                        sent++;
                    }
                }
                case FAILED -> fail(event, now, outcome.detail());
            }
        }
        return sent;
    }

    private void markPublished(OutboxEvent event, Instant now) {
        event.markPublished(now);
        published.increment();
    }

    private void fail(OutboxEvent event, Instant now, String problem) {
        event.markFailed(problem, now, properties.maxBackoff());
        failed.increment();
        log.warn("Outbox event {} ({}) not published, attempt {}: {}",
                event.getId(), event.getRoutingKey(), event.getAttempts(), problem);
    }

    /** Age of the oldest unpublished row, 0 when the outbox is empty: alert when it keeps growing. */
    private double oldestPendingSeconds() {
        Instant oldest = outbox.oldestPendingCreatedAt();
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest, clock.instant()).toSeconds());
    }
}
