package com.parkview.ruleengine.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

import java.time.Period;

/**
 * Data retention ({@code parkview.retention.*}): a scheduled job that anonymises or deletes personal data of fines and violations once it is
 * older than its period. The periods are product and legal decisions that are NOT made here: they have no default and all three are required
 * when {@code enabled} is true (see the README, "Data retention").
 *
 * @param enabled       switch the scheduled job off (default) or on; nothing is touched while it is off
 * @param dryRun        {@code true} (default): only count and log what a real run would do, change nothing
 * @param cron          when the job runs (Spring cron, server time zone); default 03:25 every night
 * @param batchSize     rows per batch; every batch is its own short transaction, so a big backlog never holds long locks
 * @param maxBatches    upper bound of batches per step and run, the rest is left for the next run
 * @param evidence      how long the plate and the picture references of the evidence of a fine are kept, counted from the issue of the
 *                      fine ({@code 25m} = 25 months, {@code 760d}, {@code 2y1m})
 * @param closedRecords how long plate, user and free text of a decided dispute, of a paid or voided fine and of its audit rows are kept,
 *                      counted from the decision, the payment or the void
 * @param violations    how long a resolved violation that never became a fine is kept, counted from its resolution; it is deleted after
 */
@Validated
@ConfigurationProperties("parkview.retention")
public record RetentionProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("true") boolean dryRun,
        @NotBlank @DefaultValue("0 25 3 * * *") String cron,
        @Min(1) @Max(10_000) @DefaultValue("1000") int batchSize,
        @Min(1) @Max(100_000) @DefaultValue("200") int maxBatches,
        Period evidence,
        Period closedRecords,
        Period violations
) {

    @AssertTrue(message = "parkview.retention.evidence, closed-records and violations are required and must be longer than zero when parkview.retention.enabled is true")
    public boolean isPeriodsSetWhenEnabled() {
        return !enabled || (positive(evidence) && positive(closedRecords) && positive(violations));
    }

    @AssertTrue(message = "parkview.retention.cron is not a valid Spring cron expression")
    public boolean isCronValid() {
        return cron == null || CronExpression.isValidExpression(cron);
    }

    private static boolean positive(Period period) {
        return period != null && !period.isNegative() && !period.isZero();
    }
}
