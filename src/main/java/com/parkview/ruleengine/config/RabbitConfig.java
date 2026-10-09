package com.parkview.ruleengine.config;

import com.parkview.ruleengine.messaging.DeadLetterConfig;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology owned by this service: its two input queues (dead-lettered to
 * {@code parkview.dlx}) and the exchanges it publishes to or consumes from. The dead-letter
 * objects themselves come from {@link DeadLetterConfig}.
 */
@Configuration
public class RabbitConfig {

    public static final String VEHICLE_EXCHANGE = "parkview.vehicle";
    public static final String VIOLATION_EXCHANGE = "parkview.violation";
    public static final String PAYMENT_EXCHANGE = "parkview.payment";

    public static final String VEHICLE_DETECTED_QUEUE = "vehicle.detected";
    public static final String PAYMENT_COMPLETED_QUEUE = "payment.completed.rule-engine";

    public static final String VEHICLE_DETECTED_KEY = "vehicle.detected";
    public static final String PAYMENT_COMPLETED_FINE_KEY = "payment.completed.fine";

    public static final String VIOLATION_CREATED_KEY = "violation.created";
    public static final String VIOLATION_RESOLVED_KEY = "violation.resolved";
    public static final String FINE_ISSUED_KEY = "fine.issued";

    @Bean
    TopicExchange vehicleExchange() {
        return new TopicExchange(VEHICLE_EXCHANGE, true, false);
    }

    @Bean
    TopicExchange violationExchange() {
        return new TopicExchange(VIOLATION_EXCHANGE, true, false);
    }

    @Bean
    TopicExchange paymentExchange() {
        return new TopicExchange(PAYMENT_EXCHANGE, true, false);
    }

    @Bean
    Queue vehicleDetectedQueue() {
        return DeadLetterConfig.durableQueue(VEHICLE_DETECTED_QUEUE);
    }

    @Bean
    Queue paymentCompletedQueue() {
        return DeadLetterConfig.durableQueue(PAYMENT_COMPLETED_QUEUE);
    }

    @Bean
    Binding vehicleDetectedBinding(Queue vehicleDetectedQueue, TopicExchange vehicleExchange) {
        return BindingBuilder.bind(vehicleDetectedQueue).to(vehicleExchange).with(VEHICLE_DETECTED_KEY);
    }

    @Bean
    Binding paymentCompletedBinding(Queue paymentCompletedQueue, TopicExchange paymentExchange) {
        return BindingBuilder.bind(paymentCompletedQueue).to(paymentExchange).with(PAYMENT_COMPLETED_FINE_KEY);
    }
}
