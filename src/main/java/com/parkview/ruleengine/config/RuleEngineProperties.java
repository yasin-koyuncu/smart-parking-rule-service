package com.parkview.ruleengine.config;

import com.parkview.ruleengine.domain.ViolationType;
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
 * @param defaultBoundaryGraceMinutes grace before a boundary / no-parking fine when the spot defines none
 * @param defaultPermitGraceMinutes   grace before a wrong-permit fine when the spot defines none
 * @param fineAmountNoParkingSek      fine amounts per violation type, SEK
 * @param expireSweepMs               delay between runs of the fine-issuing sweep
 * @param expireBatchSize             violations fined per batch (each in its own transaction)
 * @param staleMinutes                a violation not re-confirmed by a detection for this long is resolved as LEFT and is not fined
 * @param staleSweepMs                delay between runs of the stale-violation sweep
 * @param touchIntervalSeconds        minimum time between two {@code last_seen_at} writes for one violation
 * @param spotCacheTtlMinutes         how long a zone's spot polygons are cached
 */
@Validated
@ConfigurationProperties("parkview.rule-engine")
public record RuleEngineProperties(
        @DefaultValue("0.85") @DecimalMin("0.0") @DecimalMax("1.0") double iouThreshold,
        @DefaultValue("0.90") @DecimalMin("0.0") @DecimalMax("1.0") double containmentThreshold,
        @DefaultValue("0.5") @DecimalMin("0.0") @DecimalMax("1.0") double minOverlap,
        @DefaultValue("10") @PositiveOrZero int defaultBoundaryGraceMinutes,
        @DefaultValue("30") @PositiveOrZero int defaultPermitGraceMinutes,
        @DefaultValue("900") @NotNull @Positive BigDecimal fineAmountNoParkingSek,
        @DefaultValue("450") @NotNull @Positive BigDecimal fineAmountOverstaySek,
        @DefaultValue("700") @NotNull @Positive BigDecimal fineAmountWrongPermitSek,
        @DefaultValue("900") @NotNull @Positive BigDecimal fineAmountBoundaryExceededSek,
        @DefaultValue("60000") @Min(100) long expireSweepMs,
        @DefaultValue("20") @Min(1) @Max(500) int expireBatchSize,
        @DefaultValue("15") @Min(1) int staleMinutes,
        @DefaultValue("60000") @Min(100) long staleSweepMs,
        @DefaultValue("30") @PositiveOrZero int touchIntervalSeconds,
        @DefaultValue("5") @Min(1) int spotCacheTtlMinutes
) {

    public BigDecimal fineAmountFor(ViolationType type) {
        return switch (type) {
            case NO_PARKING -> fineAmountNoParkingSek;
            case OVERSTAY -> fineAmountOverstaySek;
            case WRONG_PERMIT -> fineAmountWrongPermitSek;
            case BOUNDARY_EXCEEDED -> fineAmountBoundaryExceededSek;
        };
    }

    public Duration staleAfter() {
        return Duration.ofMinutes(staleMinutes);
    }

    public Duration touchInterval() {
        return Duration.ofSeconds(touchIntervalSeconds);
    }

    public Duration spotCacheTtl() {
        return Duration.ofMinutes(spotCacheTtlMinutes);
    }
}
