package com.parkview.ruleengine.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import com.parkview.ruleengine.config.LookupProperties;
import com.parkview.ruleengine.domain.Fuel;
import com.parkview.ruleengine.domain.VehicleClass;
import com.parkview.ruleengine.domain.VehicleInfo;
import com.parkview.ruleengine.engine.EngineMetrics;
import com.parkview.ruleengine.security.SecurityProperties;
import com.parkview.ruleengine.service.Plates;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Optional;

/**
 * Vehicle registry data from lookup-service ({@code GET /internal/v1/vehicles/{plate}?country=}), authenticated
 * with {@code X-Internal-Api-Key}. Explicit connect and read timeouts; no retries (a failed lookup means "cannot
 * judge" and the next observation of the plate asks again once the negative cache entry has expired).
 *
 * <p>Found vehicles are cached for {@code cache-ttl-minutes}; "not found" and failed lookups are cached for the
 * short {@code negative-cache-ttl-seconds} so that an unreachable lookup-service is not asked for every observation.
 */
@Slf4j
@Component
class HttpVehicleDirectory implements VehicleDirectory {

    static final String API_KEY_HEADER = "X-Internal-Api-Key";

    private final RestClient client;
    private final EngineMetrics metrics;
    private final Cache<String, Optional<VehicleInfo>> cache;

    @Autowired
    HttpVehicleDirectory(LookupProperties properties, SecurityProperties security, EngineMetrics metrics,
                         RestClient.Builder clientBuilder) {
        this(buildClient(clientBuilder, properties, security), properties, metrics, Ticker.systemTicker());
    }

    HttpVehicleDirectory(RestClient client, LookupProperties properties, EngineMetrics metrics, Ticker ticker) {
        this.client = client;
        this.metrics = metrics;
        Duration positive = properties.cacheTtl();
        Duration negative = properties.negativeCacheTtl();
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.cacheMaxSize())
                .ticker(ticker)
                .expireAfter(new Expiry<String, Optional<VehicleInfo>>() {
                    @Override
                    public long expireAfterCreate(String key, Optional<VehicleInfo> value, long currentTime) {
                        return (value.isPresent() ? positive : negative).toNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, Optional<VehicleInfo> value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }

                    @Override
                    public long expireAfterRead(String key, Optional<VehicleInfo> value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }

    /** Built from Boot's builder so the call is observed and carries the {@code traceparent} when tracing is on. */
    private static RestClient buildClient(RestClient.Builder clientBuilder, LookupProperties properties,
                                          SecurityProperties security) {
        var settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .withReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
        RestClient.Builder builder = clientBuilder
                .baseUrl(properties.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.simple().build(settings));
        if (security.internalApiEnabled()) {
            builder.defaultHeader(API_KEY_HEADER, security.internalApiKey());
        }
        return builder.build();
    }

    @Override
    public Optional<VehicleInfo> lookup(String normalizedPlate, String country) {
        return cache.get(country + ':' + normalizedPlate, key -> fetch(normalizedPlate, country));
    }

    private Optional<VehicleInfo> fetch(String plate, String country) {
        try {
            return client.get()
                    .uri("/internal/v1/vehicles/{plate}?country={country}", plate, country)
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
                            log.debug("Vehicle {} is unknown to the registry", Plates.mask(plate));
                            return Optional.<VehicleInfo>empty();
                        }
                        if (!status.is2xxSuccessful()) {
                            throw new IllegalStateException("lookup-service answered " + status.value());
                        }
                        VehicleResponse body = response.bodyTo(VehicleResponse.class);
                        if (body == null) {
                            throw new IllegalStateException("lookup-service answered without a body");
                        }
                        return Optional.of(new VehicleInfo(
                                VehicleClass.fromRegistry(body.vehicleType()).orElse(null),
                                Fuel.fromRegistry(body.fuelType())));
                    });
        } catch (RuntimeException e) {
            metrics.lookupFailed();
            log.debug("Vehicle lookup for {} failed, the vehicle cannot be judged: {}", Plates.mask(plate), e.toString());
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VehicleResponse(String vehicleType, String fuelType) {
    }
}
