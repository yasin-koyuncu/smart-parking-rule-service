package com.parkview.ruleengine.service.outbox;

import com.parkview.ruleengine.domain.OutboxEvent;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessagePropertiesBuilder;

import java.nio.charset.StandardCharsets;

/**
 * The AMQP message of an outbox row: the stored JSON payload, persistent, with the outbox row id as {@code messageId}
 * (delivery is at least once, consumers de-duplicate on it) and the headers every event of the platform carries.
 */
final class OutboxMessages {

    static final String SOURCE = "rule-service";

    private OutboxMessages() {
    }

    static Message toMessage(OutboxEvent event) {
        MessageProperties props = MessagePropertiesBuilder.newInstance()
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setMessageId(event.getId().toString())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("x-event-type", event.getRoutingKey())
                .setHeader("x-source", SOURCE)
                .build();
        if (event.getRequestId() != null) {
            props.setHeader("x-request-id", event.getRequestId());
        }
        return new Message(event.getPayload().getBytes(StandardCharsets.UTF_8), props);
    }
}
