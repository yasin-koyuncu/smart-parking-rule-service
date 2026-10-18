package com.parkview.ruleengine.service;

import com.parkview.ruleengine.config.OutboxProperties;
import com.parkview.ruleengine.domain.OutboxEvent;
import com.parkview.ruleengine.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Relay semantics: publisher confirms, back-off, ordering per aggregate, headers of the former direct publish. */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final String PAYLOAD = "{\"fineId\":\"f-1\",\"amountSek\":700.00}";

    @Mock OutboxEventRepository repository;
    @Mock RabbitTemplate rabbit;

    private OutboxRelay relay;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        relay = new OutboxRelay(repository, rabbit, properties(true, false), Clock.fixed(NOW, ZoneOffset.UTC), meters);
    }

    private static OutboxProperties properties(boolean enabled, boolean retryUnroutable) {
        return new OutboxProperties(enabled, Duration.ofMillis(250), 10, Duration.ofMillis(200), Duration.ofSeconds(30),
                Duration.ofDays(14), retryUnroutable);
    }

    private static OutboxEvent row(String aggregateId, String routingKey, String requestId) {
        OutboxEvent event = OutboxEvent.create("fine", aggregateId, "parkview.violation", routingKey, PAYLOAD, requestId, NOW);
        ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
        return event;
    }

    private static OutboxEvent row(String aggregateId, String routingKey) {
        return row(aggregateId, routingKey, null);
    }

    private void send(Predicate<CorrelationData> ack) {
        doAnswer(inv -> {
            CorrelationData correlation = inv.getArgument(3);
            boolean acked = ack.test(correlation);
            correlation.getFuture().complete(new CorrelationData.Confirm(acked, acked ? null : "queue full"));
            return null;
        }).when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
    }

    private void everythingIsAcked() {
        send(correlation -> true);
    }

    @Test
    void publishesDueRowsWithTheRowIdAsMessageIdAndTheHeadersOfTheFormerDirectPublish() {
        OutboxEvent event = row("f-1", "fine.issued", "req-42");
        when(repository.lockDueBatch(NOW, 10)).thenReturn(List.of(event));
        everythingIsAcked();

        assertThat(relay.relayDueEvents()).isEqualTo(1);

        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(eq("parkview.violation"), eq("fine.issued"), message.capture(), any(CorrelationData.class));
        var props = message.getValue().getMessageProperties();
        assertThat(props.getMessageId()).isEqualTo(event.getId().toString());
        assertThat(props.getContentType()).isEqualTo("application/json");
        assertThat(props.getContentEncoding()).isEqualTo("UTF-8");
        assertThat(props.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(props.getHeaders()).containsEntry("x-event-type", "fine.issued")
                .containsEntry("x-source", "rule-service").containsEntry("x-request-id", "req-42");
        assertThat(new String(message.getValue().getBody())).isEqualTo(PAYLOAD);
        assertThat(event.getPublishedAt()).isEqualTo(NOW);
        assertThat(meters.get("rule.outbox.published").counter().count()).isEqualTo(1);
    }

    @Test
    void theRequestIdHeaderIsOmittedWhenTheEventWasNotCausedByARequest() {
        OutboxEvent event = row("v-1", "violation.created");
        when(repository.lockDueBatch(NOW, 10)).thenReturn(List.of(event));
        everythingIsAcked();

        relay.relayDueEvents();

        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(any(String.class), any(String.class), message.capture(), any(CorrelationData.class));
        assertThat(message.getValue().getMessageProperties().getHeaders()).doesNotContainKey("x-request-id");
    }

    @Test
    void rowsAreSentInTheOrderTheRepositoryClaimedThem() {
        OutboxEvent first = row("f-1", "fine.issued");
        OutboxEvent second = row("f-2", "fine.disputed");
        OutboxEvent third = row("f-3", "fine.voided");
        when(repository.lockDueBatch(NOW, 10)).thenReturn(List.of(first, second, third));
        everythingIsAcked();

        assertThat(relay.relayDueEvents()).isEqualTo(3);

        InOrder order = inOrder(rabbit);
        order.verify(rabbit).send(eq("parkview.violation"), eq("fine.issued"), any(Message.class), any(CorrelationData.class));
        order.verify(rabbit).send(eq("parkview.violation"), eq("fine.disputed"), any(Message.class), any(CorrelationData.class));
        order.verify(rabbit).send(eq("parkview.violation"), eq("fine.voided"), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void doesNothingWhenNothingIsDue() {
        when(repository.lockDueBatch(eq(NOW), anyInt())).thenReturn(List.of());

        assertThat(relay.relayDueEvents()).isZero();

        verify(rabbit, times(0)).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void aDisabledRelayDoesNotEvenClaimRows() {
        relay = new OutboxRelay(repository, rabbit, properties(false, false), Clock.fixed(NOW, ZoneOffset.UTC), meters);

        assertThat(relay.relayDueEvents()).isZero();

        org.mockito.Mockito.verifyNoInteractions(repository, rabbit);
    }

    @Test
    void aNackedMessageStaysInTheOutboxWithBackoff() {
        OutboxEvent event = row("f-1", "fine.issued");
        when(repository.lockDueBatch(NOW, 10)).thenReturn(List.of(event));
        send(correlation -> false);

        assertThat(relay.relayDueEvents()).isZero();

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(event.getLastError()).contains("nacked");
        assertThat(meters.get("rule.outbox.failed").counter().count()).isEqualTo(1);
    }

    @Test
    void theBackoffDoublesPerAttemptAndIsCapped() {
        OutboxEvent event = row("f-1", "fine.issued");

        event.markFailed("x", NOW, Duration.ofSeconds(30));
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
        event.markFailed("x", NOW, Duration.ofSeconds(30));
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(2));
        event.markFailed("x", NOW, Duration.ofSeconds(30));
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(4));
        for (int i = 0; i < 10; i++) {
            event.markFailed("x", NOW, Duration.ofSeconds(30));
        }
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(event.getAttempts()).isEqualTo(13);
    }

    private void unroutable() {
        doAnswer(inv -> {
            CorrelationData correlation = inv.getArgument(3);
            correlation.setReturned(new ReturnedMessage(inv.getArgument(2), 312, "NO_ROUTE", "parkview.violation", "fine.issued"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void anUnroutableMessageIsCountedAndTreatedAsDeliveredByDefaultLikeTheDirectPublishItReplaces() {
        OutboxEvent event = row("f-1", "fine.issued");
        when(repository.lockDueBatch(NOW, 10)).thenReturn(List.of(event));
        unroutable();

        assertThat(relay.relayDueEvents()).isEqualTo(1);

        assertThat(event.getPublishedAt()).isEqualTo(NOW);
        assertThat(meters.get("rule.outbox.unroutable").counter().count()).isEqualTo(1);
        assertThat(meters.get("rule.outbox.failed").counter().count()).isZero();
    }

    @Test
    void withRetryUnroutableAnUnroutableMessageStaysInTheOutbox() {
        relay = new OutboxRelay(repository, rabbit, properties(true, true), Clock.fixed(NOW, ZoneOffset.UTC), meters);
        OutboxEvent event = row("f-1", "fine.issued");
        when(repository.lockDueBatch(NOW, 10)).thenReturn(List.of(event));
        unroutable();

        assertThat(relay.relayDueEvents()).isZero();

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getLastError()).contains("unroutable");
        assertThat(event.getAttempts()).isEqualTo(1);
    }

    @Test
    void aMissingConfirmTimesOutAndIsRetriedLater() {
        OutboxEvent event = row("f-1", "fine.issued");
        when(repository.lockDueBatch(NOW, 10)).thenReturn(List.of(event));

        assertThat(relay.relayDueEvents()).isZero();

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getLastError()).contains("confirm");
    }

    @Test
    void anUnreachableBrokerStopsTheBatchAndKeepsEveryRow() {
        OutboxEvent first = row("f-1", "fine.issued");
        OutboxEvent second = row("f-2", "fine.issued");
        when(repository.lockDueBatch(NOW, 10)).thenReturn(List.of(first, second));
        doThrow(new AmqpConnectException(new IOException("refused")))
                .when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));

        assertThat(relay.relayDueEvents()).isZero();

        verify(rabbit, times(1)).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(second.getAttempts()).isZero();
        assertThat(first.getPublishedAt()).isNull();
        assertThat(second.getPublishedAt()).isNull();
    }

    @Test
    void onlyConfirmedRowsOfAMixedBatchArePublished() {
        OutboxEvent good = row("f-1", "fine.issued");
        OutboxEvent bad = row("f-2", "fine.issued");
        when(repository.lockDueBatch(NOW, 10)).thenReturn(List.of(good, bad));
        send(correlation -> correlation.getId().equals(good.getId().toString()));

        assertThat(relay.relayDueEvents()).isEqualTo(1);

        assertThat(good.getPublishedAt()).isEqualTo(NOW);
        assertThat(bad.getPublishedAt()).isNull();
        assertThat(bad.getAttempts()).isEqualTo(1);
    }
}
