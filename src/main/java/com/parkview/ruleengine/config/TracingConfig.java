package com.parkview.ruleengine.config;

import io.micrometer.tracing.exporter.FinishedSpan;
import io.micrometer.tracing.exporter.SpanExportingPredicate;
import io.micrometer.tracing.exporter.SpanFilter;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The one place where tracing is switched on: {@code TRACING_ENABLED=true} ({@code management.tracing.enabled}).
 *
 * <p>Boot only gates the span <em>exporters</em> on that property; the OpenTelemetry tracer, its sampler and the
 * trace-header propagation would exist regardless. {@code application.yml} therefore excludes
 * {@link OpenTelemetryTracingAutoConfiguration} and this class brings it back only when the switch is on. While
 * it is off the application has Micrometer's no-op tracer: no spans, no trace ids in the MDC, no trace headers
 * on outgoing calls, no exporter and no connection to a collector.
 *
 * <p>Probes and metric scrapes are sampled like any request but their spans are dropped before export, so
 * health checks do not drown the traces. Spans never carry request bodies or tokens, and not the raw URL either:
 * paths and queries hold plates and (for the SSE stream) tokens, so the raw-URL attributes are removed before
 * export and only the route template (the {@code uri} tag), method and status remain.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "management.tracing.enabled", havingValue = "true")
@Import(OpenTelemetryTracingAutoConfiguration.class)
public class TracingConfig {

    private static final String ACTUATOR = "/actuator";

    /** Attributes that carry the concrete URL (path and query) of a request, server or client side. */
    private static final Set<String> RAW_URL_KEYS = Set.of("http.url", "url.full", "url.path", "url.query", "http.target");

    @Bean
    SpanExportingPredicate actuatorSpansAreNotExported() {
        return span -> !isActuator(span);
    }

    @Bean
    SpanFilter rawUrlsAreNotExported() {
        return TracingConfig::withoutRawUrls;
    }

    static FinishedSpan withoutRawUrls(FinishedSpan span) {
        Map<String, Object> tags = new HashMap<>(span.getTypedTags());
        if (tags.keySet().removeAll(RAW_URL_KEYS)) {
            span.setTypedTags(tags);
        }
        return span;
    }

    static boolean isActuator(FinishedSpan span) {
        return startsWithActuator(span.getTags().get("uri")) || startsWithActuator(span.getTags().get("http.url"));
    }

    private static boolean startsWithActuator(String path) {
        return path != null && (path.equals(ACTUATOR) || path.startsWith(ACTUATOR + "/"));
    }
}
