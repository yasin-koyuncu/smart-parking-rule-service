package com.parkview.ruleengine.web;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.nio.charset.StandardCharsets;

/**
 * The texts of API errors. {@code messages/errors.properties} is the English base and is complete; {@code errors_sv.properties}
 * (and any other language) holds the translations; a region file ({@code errors_sv_SE}) only what differs. The language is
 * the one of the {@code Accept-Language} header (Spring's default resolver); an unknown language gets the English base,
 * never the server's own language. Logs stay English: only the text in the response is translated.
 */
@Configuration
public class ErrorMessagesConfig {

    @Bean("errorMessages")
    MessageSource errorMessages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages/errors");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        return source;
    }

    /** Bean validation messages ({@code {jakarta.validation...}}) come from the same bundles, in the caller's language. */
    @Bean
    LocalValidatorFactoryBean validator(MessageSource errorMessages) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setValidationMessageSource(errorMessages);
        return validator;
    }
}
