package com.parkview.ruleengine.config;

import com.parkview.ruleengine.domain.value.ParkingSpot;
import com.parkview.ruleengine.domain.type.ViolationType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Business thresholds and job tuning ({@code parkview.rule-engine.*}).
 *
 * @param iouThreshold                intersection-over-union at or above which a vehicle counts as parked inside the lines
 * @param containmentThreshold        share of the vehicle inside the spot at or above which it counts as parked inside the lines
 * @param minOverlap                  share of the vehicle that must lie in a spot for the vehicle to be considered parked in it
 * @param defaultBoundaryGraceMinutes grace before a boundary fine when the spot defines none
 * @param defaultPermitGraceMinutes   grace before a wrong-permit fine when the spot defines none
 * @param noParkingGraceMinutes       grace before a no-parking fine
 * @param overstayGraceMinutes        grace before an overstay fine
 * @param wrongVehicleTypeGraceMinutes grace before a wrong-vehicle-type fine
 * @param fineAmountNoParkingSek      fine amounts per violation type, SEK
 * @param expireSweepMs               delay between runs of the fine-issuing sweep
 * @param expireBatchSize             violations fined per batch (each in its own transaction)
 * @param staleMinutes                a violation not re-confirmed by a detection for this long is resolved as LEFT and is not fined
 * @param fineFreshnessSeconds        a violation is only fined when a detection confirmed it at most this long ago (the vehicle is demonstrably
 *                                    still there); must be well above the observation cadence plus {@code touch-interval-seconds}
 * @param staleSweepMs                delay between runs of the stale-violation sweep
 * @param touchIntervalSeconds        minimum time between two {@code last_seen_at} writes for one violation
 * @param spotCacheTtlMinutes         how long a zone's spot polygons are cached
 * @param spotCacheNegativeTtlSeconds how long a zone WITHOUT spots is cached (short, so the first spots drawn become visible quickly)
 * @param observationMaxAgeSeconds    an observation older than this (event time) is dropped: after an outage or a backlog it no longer says where the vehicle is
 * @param refineBlockHours            a plate that was fined for a reason in a zone is not given a new violation for the same reason there
 *                                    within this many hours, whatever happens to the spot (re-scan, "empty the area"); 0 turns the block off
 * @param ruleCacheTtlSeconds         how long a zone's rules and master data are cached
 * @param vehicleLookupEnabled        whether vehicle registry data may be fetched when a decision needs it
 */
@Validated
@ConfigurationProperties("parkview.rule-engine")
public record RuleEngineProperties(
        @DefaultValue("0.85") @DecimalMin("0.0") @DecimalMax("1.0") double iouThreshold,
        @DefaultValue("0.90") @DecimalMin("0.0") @DecimalMax("1.0") double containmentThreshold,
        @DefaultValue("0.5") @DecimalMin("0.0") @DecimalMax("1.0") double minOverlap,
        @DefaultValue("10") @PositiveOrZero int defaultBoundaryGraceMinutes,
        @DefaultValue("30") @PositiveOrZero int defaultPermitGraceMinutes,
        @DefaultValue("5") @PositiveOrZero int noParkingGraceMinutes,
        @DefaultValue("5") @PositiveOrZero int overstayGraceMinutes,
        @DefaultValue("10") @PositiveOrZero int wrongVehicleTypeGraceMinutes,
        @DefaultValue("900") @NotNull @Positive BigDecimal fineAmountNoParkingSek,
        @DefaultValue("450") @NotNull @Positive BigDecimal fineAmountOverstaySek,
        @DefaultValue("700") @NotNull @Positive BigDecimal fineAmountWrongPermitSek,
        @DefaultValue("900") @NotNull @Positive BigDecimal fineAmountBoundaryExceededSek,
        @DefaultValue("700") @NotNull @Positive BigDecimal fineAmountWrongVehicleTypeSek,
        @DefaultValue("60000") @Min(100) long expireSweepMs,
        @DefaultValue("20") @Min(1) @Max(500) int expireBatchSize,
        @DefaultValue("15") @Min(1) int staleMinutes,
        @DefaultValue("120") @Min(1) int fineFreshnessSeconds,
        @DefaultValue("60000") @Min(100) long staleSweepMs,
        @DefaultValue("10") @PositiveOrZero int touchIntervalSeconds,
        @DefaultValue("5") @Min(1) int spotCacheTtlMinutes,
        @DefaultValue("15") @Min(1) int spotCacheNegativeTtlSeconds,
        @DefaultValue("600") @Min(1) int observationMaxAgeSeconds,
        @DefaultValue("6") @PositiveOrZero int refineBlockHours,
        @DefaultValue("60") @Min(1) int ruleCacheTtlSeconds,
        @DefaultValue("true") boolean vehicleLookupEnabled
) {

    public BigDecimal fineAmountFor(ViolationType type) {
        return switch (type) {
            case NO_PARKING -> fineAmountNoParkingSek;
            case OVERSTAY -> fineAmountOverstaySek;
            case WRONG_PERMIT -> fineAmountWrongPermitSek;
            case BOUNDARY_EXCEEDED -> fineAmountBoundaryExceededSek;
            case WRONG_VEHICLE_TYPE -> fineAmountWrongVehicleTypeSek;
        };
    }

    /**
     * Grace before the fine: the spot's own value where it defines one (boundary, permit), otherwise the configured default.
     * A spot that defines no grace (null, or a negative value that cannot be one) uses the default; an explicit 0 is a grace of 0.
     */
    public int graceMinutesFor(ParkingSpot spot, ViolationType type) {
        return switch (type) {
            case WRONG_PERMIT -> ownOrDefault(spot.permitGraceMin(), defaultPermitGraceMinutes);
            case BOUNDARY_EXCEEDED -> ownOrDefault(spot.boundaryGraceMin(), defaultBoundaryGraceMinutes);
            case NO_PARKING -> noParkingGraceMinutes;
            case OVERSTAY -> overstayGraceMinutes;
            case WRONG_VEHICLE_TYPE -> wrongVehicleTypeGraceMinutes;
        };
    }

    private static int ownOrDefault(Integer own, int fallback) {
        return own != null && own >= 0 ? own : fallback;
    }

    public Duration staleAfter() {
        return Duration.ofMinutes(staleMinutes);
    }

    public Duration fineFreshness() {
        return Duration.ofSeconds(fineFreshnessSeconds);
    }

    public Duration observationMaxAge() {
        return Duration.ofSeconds(observationMaxAgeSeconds);
    }

    public Duration refineBlock() {
        return Duration.ofHours(refineBlockHours);
    }

    public Duration touchInterval() {
        return Duration.ofSeconds(touchIntervalSeconds);
    }

    public Duration spotCacheTtl() {
        return Duration.ofMinutes(spotCacheTtlMinutes);
    }

    public Duration spotCacheNegativeTtl() {
        return Duration.ofSeconds(spotCacheNegativeTtlSeconds);
    }

    public Duration ruleCacheTtl() {
        return Duration.ofSeconds(ruleCacheTtlSeconds);
    }
}
