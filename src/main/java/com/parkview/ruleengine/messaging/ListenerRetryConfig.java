package com.parkview.ruleengine.messaging;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.RabbitRetryTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.handler.annotation.support.MethodArgumentTypeMismatchException;
import org.springframework.retry.policy.SimpleRetryPolicy;

import java.util.Map;

/**
 * A message that can never succeed (it does not parse, fails validation, or was rejected on purpose with an
 * {@code AmqpRejectAndDontRequeueException}) is rejected to the dead-letter queue at once. Boot's listener retry would
 * otherwise retry it like a transient failure, and with the long retry window that protects
 * {@code payment.completed.fine} from a database blip (see "Events" in the README) such a message would hold up its
 * queue for minutes. The failures are looked up through their causes (a listener wraps them in a
 * {@code ListenerExecutionFailedException}). Everything else is still retried
 * {@code spring.rabbitmq.listener.simple.retry.max-attempts} times.
 */
@Configuration
class ListenerRetryConfig {

    @Bean
    RabbitRetryTemplateCustomizer noRetryForMessagesThatCannotSucceed(RabbitProperties properties) {
        return (target, template) -> {
            if (target == RabbitRetryTemplateCustomizer.Target.LISTENER) {
                template.setRetryPolicy(retryPolicy(properties.getListener().getSimple().getRetry().getMaxAttempts()));
            }
        };
    }

    static SimpleRetryPolicy retryPolicy(int maxAttempts) {
        return new SimpleRetryPolicy(maxAttempts, Map.of(
                MessageConversionException.class, false,
                org.springframework.amqp.support.converter.MessageConversionException.class, false,
                MethodArgumentNotValidException.class, false,
                MethodArgumentTypeMismatchException.class, false,
                AmqpRejectAndDontRequeueException.class, false), true, true);
    }
}
