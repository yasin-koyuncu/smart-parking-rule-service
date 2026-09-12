package com.parkview.ruleengine.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // ── Exchanges ─────────────────────────────────────────────────────────────
    public static final String VEHICLE_EXCHANGE   = "parkview.vehicle";
    public static final String VIOLATION_EXCHANGE = "parkview.violation";
    public static final String SESSION_EXCHANGE   = "parkview.session";

    // ── Queues ────────────────────────────────────────────────────────────────
    public static final String VEHICLE_DETECTED_QUEUE  = "vehicle.detected";
    public static final String VIOLATION_CREATED_QUEUE = "violation.created";

    // ── Routing keys ──────────────────────────────────────────────────────────
    public static final String VEHICLE_DETECTED_KEY  = "vehicle.detected";
    public static final String VIOLATION_CREATED_KEY = "violation.created";
    public static final String SESSION_STARTED_KEY   = "session.started";

    @Bean
    TopicExchange vehicleExchange() {
        return new TopicExchange(VEHICLE_EXCHANGE, true, false);
    }

    @Bean
    TopicExchange violationExchange() {
        return new TopicExchange(VIOLATION_EXCHANGE, true, false);
    }

    @Bean
    TopicExchange sessionExchange() {
        return new TopicExchange(SESSION_EXCHANGE, true, false);
    }

    @Bean
    Queue vehicleDetectedQueue() {
        return QueueBuilder.durable(VEHICLE_DETECTED_QUEUE)
                .withArgument("x-dead-letter-exchange", "parkview.dlx")
                .build();
    }

    @Bean
    Queue violationCreatedQueue() {
        return QueueBuilder.durable(VIOLATION_CREATED_QUEUE)
                .withArgument("x-dead-letter-exchange", "parkview.dlx")
                .build();
    }

    @Bean
    Binding vehicleDetectedBinding(Queue vehicleDetectedQueue, TopicExchange vehicleExchange) {
        return BindingBuilder.bind(vehicleDetectedQueue)
                .to(vehicleExchange)
                .with(VEHICLE_DETECTED_KEY);
    }

    @Bean
    Binding violationCreatedBinding(Queue violationCreatedQueue, TopicExchange violationExchange) {
        return BindingBuilder.bind(violationCreatedQueue)
                .to(violationExchange)
                .with(VIOLATION_CREATED_KEY);
    }

    @Bean
    Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory cf, Jackson2JsonMessageConverter converter) {
        var template = new RabbitTemplate(cf);
        template.setMessageConverter(converter);
        return template;
    }
}