package com.parkview.ruleengine.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListenerConfigurer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistrar;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.Validator;

/**
 * Messaging defaults. Boot wires the converter into both the {@code RabbitTemplate} and the
 * listener containers.
 *
 * <ul>
 *   <li>JSON via the application's ObjectMapper (java.time support, unknown properties ignored)</li>
 *   <li>{@code TypePrecedence.INFERRED}: the consumer decides the target type from its listener
 *       parameter; the publisher's Java class name in {@code __TypeId__} is ignored, so services
 *       never need each other's classes</li>
 *   <li>{@code @Payload @Valid} works on listener parameters; invalid messages are rejected to the DLQ</li>
 *   <li>publisher confirms/returns are logged (enable {@code publisher-confirm-type: correlated}
 *       and {@code publisher-returns: true} in application.yml)</li>
 * </ul>
 */
@Configuration
public class AmqpSupportConfig implements RabbitListenerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AmqpSupportConfig.class);

    private final Validator validator;

    public AmqpSupportConfig(Validator validator) {
        this.validator = validator;
    }

    @Bean
    MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        converter.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.INFERRED);
        return converter;
    }

    @Bean
    RabbitTemplateCustomizer publisherDiagnostics() {
        return template -> {
            template.setMandatory(true);
            template.setReturnsCallback(returned -> log.error(
                    "Unroutable message returned: exchange={} routingKey={} reply={}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
            template.setConfirmCallback((correlation, ack, cause) -> {
                if (!ack) {
                    log.error("Broker nacked a published message: {}", cause);
                }
            });
        };
    }

    @Override
    public void configureRabbitListeners(RabbitListenerEndpointRegistrar registrar) {
        registrar.setValidator(validator);
    }
}
