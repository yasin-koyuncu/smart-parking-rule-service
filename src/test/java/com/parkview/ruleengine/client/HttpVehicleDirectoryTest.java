package com.parkview.ruleengine.client;

import com.github.benmanes.caffeine.cache.Ticker;
import com.parkview.ruleengine.config.LookupProperties;
import com.parkview.ruleengine.domain.Fuel;
import com.parkview.ruleengine.domain.VehicleClass;
import com.parkview.ruleengine.domain.VehicleInfo;
import com.parkview.ruleengine.engine.EngineMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpVehicleDirectoryTest {

    private static final String BODY = """
            {"plate":"XYZ789","country":"SE","vehicleType":"Personbil","fuelType":"El","brand":"Volvo","model":"V90",
             "modelYear":2022,"dataSource":"mock","fromCache":false}
            """;

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private MockRestServiceServer server;
    private HttpVehicleDirectory directory;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://lookup.test")
                .defaultHeader(HttpVehicleDirectory.API_KEY_HEADER, "secret");
        server = MockRestServiceServer.bindTo(builder).build();
        LookupProperties properties = new LookupProperties("http://lookup.test", 500, 1500, 60, 30, 100);
        directory = new HttpVehicleDirectory(builder.build(), properties, new EngineMetrics(registry), ticker);
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    @Test
    void aFoundVehicleIsMappedAndTheInternalKeyAndCountryAreSent() {
        server.expect(once(), requestTo("http://lookup.test/internal/v1/vehicles/XYZ789?country=SE"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Api-Key", "secret"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        Optional<VehicleInfo> info = directory.lookup("XYZ789", "SE");

        assertThat(info).contains(new VehicleInfo(VehicleClass.CAR, Fuel.ELECTRIC));
        server.verify();
    }

    @Test
    void ownerDataInTheResponseIsNeverRead() {
        server.expect(once(), requestTo("http://lookup.test/internal/v1/vehicles/XYZ789?country=SE"))
                .andRespond(withSuccess("""
                        {"vehicleType":"Lastbil","fuelType":"Diesel","owner":{"name":"Someone"},"somethingNew":1}
                        """, MediaType.APPLICATION_JSON));

        assertThat(directory.lookup("XYZ789", "SE")).contains(new VehicleInfo(VehicleClass.TRUCK, Fuel.OTHER));
    }

    @Test
    void foundVehiclesAreCachedForTheConfiguredTime() {
        server.expect(once(), requestTo("http://lookup.test/internal/v1/vehicles/XYZ789?country=SE"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        directory.lookup("XYZ789", "SE");
        advance(Duration.ofMinutes(59));
        assertThat(directory.lookup("XYZ789", "SE")).isPresent();
        server.verify();

        server.reset();
        server.expect(once(), requestTo("http://lookup.test/internal/v1/vehicles/XYZ789?country=SE"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));
        advance(Duration.ofMinutes(2));
        directory.lookup("XYZ789", "SE");
        server.verify();
    }

    @Test
    void anUnknownVehicleIsCachedBrieflyAndIsNotAFailure() {
        server.expect(once(), requestTo("http://lookup.test/internal/v1/vehicles/NOPE11?country=SE"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(directory.lookup("NOPE11", "SE")).isEmpty();
        assertThat(directory.lookup("NOPE11", "SE")).isEmpty();
        server.verify();
        assertThat(registry.counter("ruleengine.vehicle.lookups.failed").count()).isZero();

        server.reset();
        server.expect(once(), requestTo("http://lookup.test/internal/v1/vehicles/NOPE11?country=SE"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));
        advance(Duration.ofSeconds(31));
        assertThat(directory.lookup("NOPE11", "SE")).isPresent();
        server.verify();
    }

    @Test
    void serverErrorsAndUnauthorizedCountAsFailedLookupsAndFailOpen() {
        server.expect(once(), requestTo("http://lookup.test/internal/v1/vehicles/AAA111?country=SE"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(once(), requestTo("http://lookup.test/internal/v1/vehicles/BBB222?country=SE"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(directory.lookup("AAA111", "SE")).isEmpty();
        assertThat(directory.lookup("BBB222", "SE")).isEmpty();

        assertThat(registry.counter("ruleengine.vehicle.lookups.failed").count()).isEqualTo(2.0);
    }

    @Test
    void anUnreachableServiceFailsOpenAndIsNotAskedAgainWithinTheNegativeTtl() {
        server.expect(once(), requestTo("http://lookup.test/internal/v1/vehicles/CCC333?country=SE"))
                .andRespond(request -> {
                    throw new IOException("connection refused");
                });

        assertThat(directory.lookup("CCC333", "SE")).isEmpty();
        assertThat(directory.lookup("CCC333", "SE")).isEmpty();

        server.verify();
        assertThat(registry.counter("ruleengine.vehicle.lookups.failed").count()).isEqualTo(1.0);
    }

    @Test
    void theCountryIsPartOfTheCacheKey() {
        server.expect(once(), requestTo("http://lookup.test/internal/v1/vehicles/ABC123?country=SE"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://lookup.test/internal/v1/vehicles/ABC123?country=NO"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(directory.lookup("ABC123", "SE")).isPresent();
        assertThat(directory.lookup("ABC123", "NO")).isEmpty();
        server.verify();
    }

    @Test
    void aSuccessWithAnEmptyBodyIsAFailure() {
        server.expect(once(), requestTo("http://lookup.test/internal/v1/vehicles/DDD444?country=SE"))
                .andRespond(withStatus(HttpStatus.OK));

        assertThat(directory.lookup("DDD444", "SE")).isEmpty();
        assertThat(registry.counter("ruleengine.vehicle.lookups.failed").count()).isEqualTo(1.0);
    }
}
