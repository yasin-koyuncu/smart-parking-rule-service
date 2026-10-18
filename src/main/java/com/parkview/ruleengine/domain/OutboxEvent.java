package com.parkview.ruleengine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A message to publish, written in the same transaction as the state change that caused it. The row id doubles as the
 * AMQP {@code messageId}, so consumers can de-duplicate redeliveries. The table's {@code seq} column (insertion order) is not
 * mapped: only the relay's claim query uses it. {@code requestId} keeps the {@code x-request-id} header of the request that caused it.
 */
@Entity
@Table(name = "rule_outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(nullable = false)
    private String exchange;

    @Column(name = "routing_key", nullable = false)
    private String routingKey;

    @Column(nullable = false)
    private String payload;

    @Column(name = "request_id")
    private String requestId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    public static OutboxEvent create(String aggregateType, String aggregateId, String exchange, String routingKey,
                                     String jsonPayload, String requestId, Instant now) {
        OutboxEvent event = new OutboxEvent();
        event.aggregateType = Objects.requireNonNull(aggregateType);
        event.aggregateId = Objects.requireNonNull(aggregateId);
        event.exchange = Objects.requireNonNull(exchange);
        event.routingKey = Objects.requireNonNull(routingKey);
        event.payload = Objects.requireNonNull(jsonPayload);
        event.requestId = requestId;
        event.createdAt = now;
        event.nextAttemptAt = now;
        return event;
    }

    public void markPublished(Instant now) {
        publishedAt = now;
        lastError = null;
    }

    /** Records a failed attempt and delays the next one: 1 s, 2 s, 4 s ... capped at {@code maxBackoff}. */
    public void markFailed(String error, Instant now, Duration maxBackoff) {
        attempts++;
        long seconds = 1L << Math.min(attempts - 1, 20);
        Duration backoff = Duration.ofSeconds(seconds);
        nextAttemptAt = now.plus(backoff.compareTo(maxBackoff) > 0 ? maxBackoff : backoff);
        lastError = error == null ? null : error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH));
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof OutboxEvent other && id != null && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return OutboxEvent.class.hashCode();
    }
}
