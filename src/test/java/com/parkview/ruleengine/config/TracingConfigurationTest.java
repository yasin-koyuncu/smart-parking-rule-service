package com.parkview.ruleengine.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.NoopTracerAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryEventPublisherBeansApplicationListener;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tracing is off by default (no tracer provider, no exporter, nothing that could open a connection) and is switched
 * on by {@code TRACING_ENABLED} alone. Runs the real {@code application.yml} against Boot's tracing
 * auto-configurations; the runner does not apply {@code spring.autoconfigure.exclude}, so the excluded
 * {@link OpenTelemetryTracingAutoConfiguration} is left out of its list and its exclusion is asserted separately.
 */
class TracingConfigurationTest {

    private static final String[] ON_WITHOUT_OTLP = {"TRACING_ENABLED=true", "TRACING_SAMPLING=1.0",
            "management.otlp.tracing.export.enabled=false"};

    @BeforeAll
    static void publishTraceEventsToTheLogContext() {
        // what a started application does through Boot's ApplicationListener (see also the initializer below):
        // lets the MDC follow the current span
        OpenTelemetryEventPublisherBeansApplicationListener.addWrapper();
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withInitializer(context -> context.addApplicationListener(
                    new OpenTelemetryEventPublisherBeansApplicationListener()))
            .withConfiguration(AutoConfigurations.of(ObservationAutoConfiguration.class,
                    OpenTelemetryAutoConfiguration.class, MicrometerTracingAutoConfiguration.class,
                    OtlpTracingAutoConfiguration.class, NoopTracerAutoConfiguration.class))
            .withUserConfiguration(TracingConfig.class);

    @Test
    void applicationYmlExcludesBootsTracerAndTheSwitchDefaultsToOff() {
        runner.run(context -> {
            Binder binder = Binder.get(context.getEnvironment());
            assertThat(binder.bind("spring.autoconfigure.exclude", String[].class).get())
                    .contains(OpenTelemetryTracingAutoConfiguration.class.getName());
            assertThat(binder.bind("management.tracing.enabled", Boolean.class).get()).isFalse();
        });
    }

    @Test
    void offByDefaultThereIsNoTracerProviderAndNoExporter() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(SdkTracerProvider.class)).isEmpty();
            assertThat(context.getBeansOfType(SpanExporter.class)).isEmpty();
            assertThat(context.getBean(Tracer.class)).isSameAs(Tracer.NOOP);
        });
    }

    @Test
    void theSwitchCreatesTheOtlpExporterWithTheDefaultEndpoint() {
        runner.withPropertyValues("TRACING_ENABLED=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(SdkTracerProvider.class)).hasSize(1);
            assertThat(context.getBeansOfType(SpanExporter.class)).hasSize(1);
            assertThat(context.getEnvironment().getProperty("management.otlp.tracing.endpoint"))
                    .isEqualTo("http://localhost:4318/v1/traces");
        });
    }

    @Test
    void theEndpointFollowsTheStandardOtlpVariables() {
        runner.withPropertyValues("OTEL_EXPORTER_OTLP_ENDPOINT=http://collector:4318")
                .run(context -> assertThat(context.getEnvironment().getProperty("management.otlp.tracing.endpoint"))
                        .isEqualTo("http://collector:4318/v1/traces"));
        runner.withPropertyValues("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://tempo:4318/custom")
                .run(context -> assertThat(context.getEnvironment().getProperty("management.otlp.tracing.endpoint"))
                        .isEqualTo("http://tempo:4318/custom"));
    }

    @Test
    void anObservationBecomesAnExportedSpanWithTheTraceIdInTheLogContext() {
        runner.withPropertyValues(ON_WITHOUT_OTLP)
                .withUserConfiguration(CollectingExporterConfig.class)
                .run(context -> {
                    ObservationRegistry registry = context.getBean(ObservationRegistry.class);
                    String[] mdc = new String[2];
                    Observation.createNotStarted("tracing.test", registry).observe(() -> {
                        mdc[0] = MDC.get("traceId");
                        mdc[1] = MDC.get("spanId");
                    });
                    assertThat(mdc[0]).hasSize(32);
                    assertThat(mdc[1]).hasSize(16);
                    assertThat(MDC.get("traceId")).isNull();
                    assertThat(exported(context)).extracting(SpanData::getTraceId).contains(mdc[0]);
                });
    }

    @Test
    void actuatorSpansAreNotExportedButOthersAre() {
        runner.withPropertyValues(ON_WITHOUT_OTLP)
                .withUserConfiguration(CollectingExporterConfig.class)
                .run(context -> {
                    Tracer tracer = context.getBean(Tracer.class);
                    tracer.nextSpan().name("http get /actuator/health").tag("http.url", "/actuator/health").start().end();
                    tracer.nextSpan().name("http get /actuator/prometheus").tag("uri", "/actuator/prometheus").start().end();
                    tracer.nextSpan().name("http get /api/v1/things").tag("uri", "/api/v1/things").start().end();
                    assertThat(exported(context)).extracting(SpanData::getName).containsExactly("http get /api/v1/things");
                });
    }

    @Test
    void rawUrlsNeverReachTheExporterButTheRouteTemplateDoes() {
        runner.withPropertyValues(ON_WITHOUT_OTLP)
                .withUserConfiguration(CollectingExporterConfig.class)
                .run(context -> {
                    Tracer tracer = context.getBean(Tracer.class);
                    tracer.nextSpan().name("http get /api/v1/things/{plate}")
                            .tag("uri", "/api/v1/things/{plate}")
                            .tag("http.url", "/api/v1/things/ABC123?token=secret")
                            .tag("url.full", "http://peer/api/v1/things/ABC123")
                            .tag("method", "GET").start().end();
                    assertThat(exported(context)).singleElement().satisfies(span -> {
                        assertThat(span.getAttributes().asMap().keySet().stream().map(Object::toString))
                                .contains("uri", "method")
                                .doesNotContain("http.url", "url.full");
                        assertThat(span.toString()).doesNotContain("ABC123").doesNotContain("secret");
                    });
                });
    }

    @Test
    void outgoingRestClientCallsCarryTheTraceparent() {
        runner.withPropertyValues(ON_WITHOUT_OTLP)
                .withUserConfiguration(CollectingExporterConfig.class)
                .run(context -> {
                    RestClient.Builder builder = RestClient.builder()
                            .observationRegistry(context.getBean(ObservationRegistry.class));
                    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
                    server.expect(requestTo("http://peer/ping")).andExpect(method(HttpMethod.GET))
                            .andExpect(header("traceparent", matchesPattern("00-[0-9a-f]{32}-[0-9a-f]{16}-01")))
                            .andRespond(withSuccess());
                    builder.build().get().uri("http://peer/ping").retrieve().toBodilessEntity();
                    server.verify();
                });
    }

    private static List<SpanData> exported(AssertableApplicationContext context) {
        context.getBean(SdkTracerProvider.class).forceFlush().join(5, TimeUnit.SECONDS);
        return context.getBean(CollectingExporter.class).spans;
    }

    @Configuration(proxyBeanMethods = false)
    static class CollectingExporterConfig {
        @Bean
        CollectingExporter collectingExporter() {
            return new CollectingExporter();
        }
    }

    static class CollectingExporter implements SpanExporter {
        final List<SpanData> spans = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            spans.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
