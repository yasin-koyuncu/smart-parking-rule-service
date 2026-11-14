package com.parkview.ruleengine.service.fine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkview.platform.outbox.OutboxEvent;
import com.parkview.platform.outbox.OutboxEventRepository;
import com.parkview.platform.outbox.OutboxService;
import com.parkview.ruleengine.domain.type.ViolationType;
import com.parkview.ruleengine.dto.event.FineIssuedEvent;
import com.parkview.ruleengine.dto.event.FineVoidedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The outbox row of a fine event holds exactly the bytes the former direct publish put on the wire (the payload contents of the catalogue). */
class FineEventPayloadTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final OutboxService service = new OutboxService(repository, mapper, Clock.fixed(NOW, ZoneOffset.UTC));

    private OutboxEvent enqueue(Object payload) {
        when(repository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        return service.enqueue(FineEvents.AGGREGATE_FINE, UUID.fromString("11111111-2222-3333-4444-555555555555"),
                "parkview.violation", "fine.voided", payload);
    }

    @Test
    void theRowIsTheFineAggregateWithTheSerialisedPayload() {
        OutboxEvent row = enqueue(new FineVoidedEvent(UUID.randomUUID(), "ABC123", "z1", "z1", NOW));

        assertThat(row.getAggregateType()).isEqualTo("fine");
        assertThat(row.getAggregateId()).isEqualTo("11111111-2222-3333-4444-555555555555");
        assertThat(row.getRoutingKey()).isEqualTo("fine.voided");
        assertThat(row.getPayload()).contains("\"occurredAt\":\"2026-10-09T10:00:00Z\"").contains("\"plate\":\"ABC123\"");
    }

    @Test
    void thePayloadIsByteIdenticalToWhatTheAmqpJsonConverterProducedBefore() {
        FineIssuedEvent payload = new FineIssuedEvent(UUID.randomUUID(), UUID.randomUUID(), "ABC123", null, "z1", "Storgatan 1", "z1", "Storgatan 1",
                new BigDecimal("700.00"), "SEK", NOW.toEpochMilli(), ViolationType.WRONG_PERMIT, null);
        Message before = new Jackson2JsonMessageConverter(mapper).toMessage(payload, new MessageProperties());

        OutboxEvent row = enqueue(payload);

        assertThat(row.getPayload()).isEqualTo(new String(before.getBody(), StandardCharsets.UTF_8));
        assertThat(row.getPayload()).contains("\"amount\":700.00").contains("\"currency\":\"SEK\"").contains("\"userId\":null")
                .contains("\"violationType\":\"WRONG_PERMIT\"").contains("\"sessionId\":null");
    }
}
