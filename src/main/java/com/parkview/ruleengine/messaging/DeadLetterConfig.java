package com.parkview.ruleengine.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Platform-wide dead-letter topology. Every service declares the same (idempotent) objects, so
 * a message that exhausts its retries is parked in {@code parkview.dlq} (original routing key and
 * {@code x-death} headers preserved) instead of being lost or looping forever.
 */
@Configuration
public class DeadLetterConfig {

    public static final String DLX = "parkview.dlx";
    public static final String DLQ = "parkview.dlq";

    @Bean
    TopicExchange deadLetterExchange() {
        return new TopicExchange(DLX, true, false);
    }

    @Bean
    Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    Binding deadLetterBinding(Queue deadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with("#");
    }

    /** A durable work queue whose rejected messages are dead-lettered to {@link #DLX}. */
    public static Queue durableQueue(String name) {
        return QueueBuilder.durable(name).deadLetterExchange(DLX).build();
    }
}
