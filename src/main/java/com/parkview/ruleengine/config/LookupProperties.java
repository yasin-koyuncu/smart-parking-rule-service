package com.parkview.ruleengine.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Connection to lookup-service for vehicle registry data ({@code parkview.lookup.*}).
 *
 * @param baseUrl               lookup-service base URL (env {@code LOOKUP_SERVICE_URL})
 * @param connectTimeoutMs      connect timeout
 * @param readTimeoutMs         read timeout; a slower answer counts as a failed lookup
 * @param cacheTtlMinutes       how long a found vehicle is cached
 * @param negativeCacheTtlSeconds how long "not found" and failed lookups are cached
 * @param cacheMaxSize          maximum number of cached plates
 * @param internalApiKey        the key of lookup-service (env {@code LOOKUP_INTERNAL_API_KEY}), sent as {@code X-Internal-Api-Key};
 *                              blank: no key is sent (lookup-service then refuses every call)
 */
@Validated
@ConfigurationProperties("parkview.lookup")
public record LookupProperties(
        @DefaultValue("http://localhost:8089") @NotBlank String baseUrl,
        @DefaultValue("500") @Min(50) int connectTimeoutMs,
        @DefaultValue("1500") @Min(50) int readTimeoutMs,
        @DefaultValue("60") @Min(1) int cacheTtlMinutes,
        @DefaultValue("60") @Min(1) int negativeCacheTtlSeconds,
        @DefaultValue("10000") @Min(1) long cacheMaxSize,
        String internalApiKey
) {

    public Duration cacheTtl() {
        return Duration.ofMinutes(cacheTtlMinutes);
    }

    public Duration negativeCacheTtl() {
        return Duration.ofSeconds(negativeCacheTtlSeconds);
    }
}
