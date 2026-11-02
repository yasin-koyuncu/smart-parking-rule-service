package com.parkview.ruleengine.service.retention;

import com.parkview.ruleengine.config.RetentionProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Data retention of fines and violations (see {@link RuleRetention} for what is anonymised or deleted). The schedule is
 * {@code parkview.retention.cron}; {@link #run()} checks {@code parkview.retention.enabled}, so nothing runs while it is off (default).
 *
 * <p>One run at a time across all instances (a Postgres advisory lock, see {@link RetentionLock}): an instance that does not get the lock
 * skips the run. {@code dry-run} (the default) only counts. The log carries counts and table names, never a plate, an id or any text of a
 * row, and each run leaves an audit line at the start and at the end.
 */
@Slf4j
@Component
public class RetentionJob {

    static final String SERVICE = "rule-service";

    /** Outcome of {@link #run()}. */
    public enum Status {
        /** {@code parkview.retention.enabled} is false: nothing was touched. */
        DISABLED,
        /** another instance holds the lock. */
        SKIPPED,
        COMPLETED,
        FAILED
    }

    /** @param counts rows handled per step, or in a dry run the rows that would be; null unless {@code COMPLETED} */
    public record Result(Status status, boolean dryRun, RuleRetention.Counts counts) {
        static Result of(Status status, boolean dryRun) {
            return new Result(status, dryRun, null);
        }
    }

    private final RetentionProperties properties;
    private final RetentionLock lock;
    private final RuleRetention retention;
    private final Clock clock;
    private final MeterRegistry meters;
    private final Timer timer;
    private final AtomicReference<Instant> lastSuccess = new AtomicReference<>();

    public RetentionJob(RetentionProperties properties, RetentionLock lock, RuleRetention retention, Clock clock, MeterRegistry meters) {
        this.properties = properties;
        this.lock = lock;
        this.retention = retention;
        this.clock = clock;
        this.meters = meters;
        this.timer = Timer.builder("retention.run").tag("service", SERVICE).register(meters);
        Gauge.builder("retention.last.success.timestamp", lastSuccess, ref -> ref.get() == null ? 0 : ref.get().getEpochSecond())
                .tag("service", SERVICE).baseUnit("seconds")
                .description("Unix time of the last run that completed (dry runs included), 0 before the first").register(meters);
    }

    @Scheduled(cron = "${parkview.retention.cron}")
    void scheduledRun() {
        run();
    }

    public Result run() {
        boolean dryRun = properties.dryRun();
        if (!properties.enabled()) {
            return Result.of(Status.DISABLED, dryRun);
        }
        AtomicReference<Result> result = new AtomicReference<>();
        boolean ran;
        try {
            ran = lock.runIfFree(() -> result.set(runLocked(dryRun)));
        } catch (RuntimeException e) {
            log.error("Retention run could not start: {}", e.getClass().getSimpleName());
            return Result.of(Status.FAILED, dryRun);
        }
        if (!ran) {
            log.info("Retention run skipped: another instance holds the lock");
            return Result.of(Status.SKIPPED, dryRun);
        }
        return result.get();
    }

    private Result runLocked(boolean dryRun) {
        Instant start = clock.instant();
        Instant evidenceCutoff = start.atZone(ZoneOffset.UTC).minus(properties.evidence()).toInstant();
        Instant recordsCutoff = start.atZone(ZoneOffset.UTC).minus(properties.closedRecords()).toInstant();
        Instant violationsCutoff = start.atZone(ZoneOffset.UTC).minus(properties.violations()).toInstant();
        log.info("AUDIT retention run started dryRun={} batchSize={} maxBatches={}", dryRun, properties.batchSize(), properties.maxBatches());
        try {
            RuleRetention.Counts counts = timer.recordCallable(() -> dryRun
                    ? retention.count(evidenceCutoff, recordsCutoff, violationsCutoff)
                    : retention.run(evidenceCutoff, recordsCutoff, violationsCutoff));
            String anonymise = dryRun ? "would_anonymise" : "anonymised";
            count("fine_evidence", anonymise, counts.evidence());
            count("fine_disputes", anonymise, counts.disputes());
            count("fines", anonymise, counts.fines());
            count("fine_actions", anonymise, counts.actions());
            count("spot_violations", dryRun ? "would_delete" : "deleted", counts.violations());
            lastSuccess.set(clock.instant());
            log.info("AUDIT retention run finished dryRun={} fine_evidence={} fine_disputes={} fines={} fine_actions={} spot_violations={} backlogRemains={}",
                    dryRun, counts.evidence(), counts.disputes(), counts.fines(), counts.actions(), counts.violations(), counts.backlogRemains());
            return new Result(Status.COMPLETED, dryRun, counts);
        } catch (Exception e) {
            // the exception text of a database error can quote row values, so only its type is logged
            log.error("AUDIT retention run failed dryRun={} cause={}, batches already committed stay", dryRun, e.getClass().getSimpleName());
            return Result.of(Status.FAILED, dryRun);
        }
    }

    private void count(String table, String action, long rows) {
        meters.counter("retention.rows", "service", SERVICE, "table", table, "action", action).increment(rows);
    }
}
