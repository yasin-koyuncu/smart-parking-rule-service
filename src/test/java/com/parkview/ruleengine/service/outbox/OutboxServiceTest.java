package com.parkview.ruleengine.service.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkview.ruleengine.domain.OutboxEvent;
import com.parkview.ruleengine.domain.type.ViolationType;
import com.parkview.ruleengine.dto.event.FineIssuedEvent;
import com.parkview.ruleengine.dto.event.FineVoidedEvent;
import com.parkview.ruleengine.repository.OutboxEventRepository;
import com.parkview.ruleengine.web.CorrelationIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The outbox row holds exactly the bytes the former direct publish put on the wire, plus the request id. */
class OutboxServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final OutboxService service = new OutboxService(repository, mapper, Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private OutboxEvent enqueue(Object payload) {
        when(repository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        return service.enqueue(OutboxService.AGGREGATE_FINE, UUID.fromString("11111111-2222-3333-4444-555555555555"),
                "parkview.violation", "fine.voided", payload);
    }

    @Test
    void theRowCarriesAggregateRoutingTimeAndTheSerialisedPayload() {
        FineVoidedEvent payload = new FineVoidedEvent(UUID.randomUUID(), "ABC123", "z1", NOW);

        OutboxEvent row = enqueue(payload);

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(row);
        assertThat(row.getAggregateType()).isEqualTo("fine");
        assertThat(row.getAggregateId()).isEqualTo("11111111-2222-3333-4444-555555555555");
        assertThat(row.getExchange()).isEqualTo("parkview.violation");
        assertThat(row.getRoutingKey()).isEqualTo("fine.voided");
        assertThat(row.getCreatedAt()).isEqualTo(NOW);
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getPayload()).contains("\"occurredAt\":\"2026-10-09T10:00:00Z\"").contains("\"plate\":\"ABC123\"");
    }

    @Test
    void thePayloadIsByteIdenticalToWhatTheAmqpJsonConverterProducedBefore() {
        FineIssuedEvent payload = new FineIssuedEvent(UUID.randomUUID(), UUID.randomUUID(), "ABC123", null, "z1", "Storgatan 1",
                new BigDecimal("700.00"), NOW.toEpochMilli(), ViolationType.WRONG_PERMIT);
        Message before = new Jackson2JsonMessageConverter(mapper).toMessage(payload, new MessageProperties());

        OutboxEvent row = enqueue(payload);

        assertThat(row.getPayload()).isEqualTo(new String(before.getBody(), java.nio.charset.StandardCharsets.UTF_8));
        assertThat(row.getPayload()).contains("\"amountSek\":700.00").contains("\"userId\":null").contains("\"violationType\":\"WRONG_PERMIT\"");
    }

    @Test
    void theRequestIdOfTheCallerIsKept() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "req-7");

        assertThat(enqueue(new FineVoidedEvent(UUID.randomUUID(), "ABC123", "z1", NOW)).getRequestId()).isEqualTo("req-7");
    }

    @Test
    void noRequestMeansNoRequestId() {
        assertThat(enqueue(new FineVoidedEvent(UUID.randomUUID(), "ABC123", "z1", NOW)).getRequestId()).isNull();
    }
}
