package com.parkview.ruleengine.messaging;

import com.parkview.ruleengine.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Publishes domain events.
 *
 * <ul>
 *   <li>Inside a transaction the message is sent <b>after commit</b>, so consumers never see
 *       state that is later rolled back. (For events that must not be lost even if the broker is
 *       down at that moment - payments - use the transactional outbox instead.)</li>
 *   <li>Every message is persistent and carries a unique {@code messageId} (idempotency key for
 *       consumers) and the headers {@code x-event-type}, {@code x-source}, {@code x-request-id}.</li>
 * </ul>
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final String source;

    public EventPublisher(RabbitTemplate rabbitTemplate, @Value("${spring.application.name}") String source) {
        this.rabbitTemplate = rabbitTemplate;
        this.source = source;
    }

    public void publish(String exchange, String routingKey, Object payload) {
        // capture the request id now: after-commit callbacks may run when the MDC is already cleared
        String requestId = MDC.get(CorrelationIdFilter.MDC_KEY);
        Runnable send = () -> send(exchange, routingKey, payload, requestId);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    private void send(String exchange, String routingKey, Object payload, String requestId) {
        MessagePostProcessor headers = message -> {
            var props = message.getMessageProperties();
            props.setMessageId(UUID.randomUUID().toString());
            props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            props.setHeader("x-event-type", routingKey);
            props.setHeader("x-source", source);
            if (requestId != null) {
                props.setHeader("x-request-id", requestId);
            }
            return message;
        };
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, payload, headers);
        } catch (RuntimeException e) {
            // The business transaction already committed; do not turn a broker hiccup into a failed request.
            log.error("Could not publish {} to {}: {}", routingKey, exchange, e.toString());
        }
    }
}
