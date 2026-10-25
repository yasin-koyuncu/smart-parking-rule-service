package com.parkview.ruleengine.messaging;

import com.parkview.ruleengine.web.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.RabbitRetryTemplateCustomizer;
import org.springframework.core.MethodParameter;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.retry.RetryContext;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which listener failures are retried: those that can resolve themselves, not a message that can never succeed, also
 * when the listener wrapped the failure in a {@code ListenerExecutionFailedException}.
 */
class ListenerRetryConfigTest {

    private final SimpleRetryPolicy policy = ListenerRetryConfig.retryPolicy(6);

    private boolean retried(Throwable failure) {
        RetryContext context = policy.open(null);
        policy.registerThrowable(context, failure);
        return policy.canRetry(context);
    }

    private static ListenerExecutionFailedException wrapped(Throwable cause) {
        return new ListenerExecutionFailedException("Listener method failed", cause);
    }

    private static MethodArgumentNotValidException invalidPayload() {
        try {
            MethodParameter parameter = new MethodParameter(
                    ListenerRetryConfigTest.class.getDeclaredMethod("wrapped", Throwable.class), 0);
            return new MethodArgumentNotValidException(MessageBuilder.withPayload("x").build(), parameter);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void anUnreadableMessageIsNotRetried() {
        assertThat(retried(wrapped(new MessageConversionException("bad json")))).isFalse();
        assertThat(retried(wrapped(new org.springframework.messaging.converter.MessageConversionException("bad")))).isFalse();
    }

    @Test
    void aMessageThatFailsValidationIsNotRetried() {
        assertThat(retried(wrapped(invalidPayload()))).isFalse();
    }

    @Test
    void aMessageRejectedOnPurposeIsNotRetried() {
        assertThat(retried(wrapped(new AmqpRejectAndDontRequeueException("Unreadable event")))).isFalse();
        assertThat(retried(new AmqpRejectAndDontRequeueException("unwrapped"))).isFalse();
    }

    @Test
    void failuresThatCanResolveThemselvesAreRetried() {
        assertThat(retried(wrapped(new TransientDataAccessResourceException("db down")))).isTrue();
        assertThat(retried(wrapped(new BusinessRuleException("Unknown zone: z9")))).isTrue();
    }

    @Test
    void theRetriesStopAtTheConfiguredNumberOfAttempts() {
        SimpleRetryPolicy three = ListenerRetryConfig.retryPolicy(3);
        RetryContext context = three.open(null);
        for (int i = 0; i < 3; i++) {
            three.registerThrowable(context, wrapped(new TransientDataAccessResourceException("db down")));
        }

        assertThat(three.canRetry(context)).isFalse();
    }

    @Test
    void theCustomizerSetsThePolicyOnTheListenerTemplateOnly() {
        RabbitProperties properties = new RabbitProperties();
        properties.getListener().getSimple().getRetry().setMaxAttempts(4);
        RabbitRetryTemplateCustomizer customizer = new ListenerRetryConfig().noRetryForMessagesThatCannotSucceed(properties);
        RetryTemplate listener = new RetryTemplate();
        RetryTemplate publisher = new RetryTemplate();

        customizer.customize(RabbitRetryTemplateCustomizer.Target.LISTENER, listener);
        customizer.customize(RabbitRetryTemplateCustomizer.Target.SENDER, publisher);

        assertThat(listener).extracting("retryPolicy.maxAttempts").isEqualTo(4);
        assertThat(publisher).extracting("retryPolicy.maxAttempts").isEqualTo(3);   // the template's own default
    }
}
