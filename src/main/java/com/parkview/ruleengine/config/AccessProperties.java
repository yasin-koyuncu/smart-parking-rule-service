package com.parkview.ruleengine.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Object-level access settings ({@code parkview.access.*}).
 *
 * @param platesCacheTtl user's registered plates are cached this long (a new plate is visible within it)
 * @param platesCacheMax maximum number of cached users
 * @param sitesCacheTtl  an operator's sites are cached this long (a site added or removed applies within it)
 * @param sitesCacheMax  maximum number of cached operators
 */
@Validated
@ConfigurationProperties("parkview.access")
public record AccessProperties(
        @DefaultValue("30s") @NotNull Duration platesCacheTtl,
        @DefaultValue("10000") @Min(1) long platesCacheMax,
        @DefaultValue("30s") @NotNull Duration sitesCacheTtl,
        @DefaultValue("10000") @Min(1) long sitesCacheMax
) {
}
