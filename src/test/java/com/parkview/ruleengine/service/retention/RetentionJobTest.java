package com.parkview.ruleengine.service.retention;

import com.parkview.ruleengine.config.RetentionProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RetentionJobTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final Period P = Period.ofDays(30);

    private final RuleRetention retention = mock(RuleRetention.class);
    private final RetentionLock lock = action -> {
        action.run();
        return true;
    };

    private RetentionJob job(boolean enabled, boolean dryRun, RetentionLock lock) {
        RetentionProperties properties = new RetentionProperties(enabled, dryRun, "0 25 3 * * *", 10, 10, P, P, P);
        return new RetentionJob(properties, lock, retention, Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());
    }

    @Test
    void disabledTouchesNothing() {
        RetentionJob.Result result = job(false, false, lock).run();

        assertThat(result.status()).isEqualTo(RetentionJob.Status.DISABLED);
        verifyNoInteractions(retention);
    }

    @Test
    void aDryRunOnlyCounts() {
        RuleRetention.Counts counts = new RuleRetention.Counts(1, 2, 3, 4, 5, false);
        when(retention.count(any(), any(), any())).thenReturn(counts);

        RetentionJob.Result result = job(true, true, lock).run();

        assertThat(result.status()).isEqualTo(RetentionJob.Status.COMPLETED);
        assertThat(result.counts()).isEqualTo(counts);
        verify(retention, never()).run(any(), any(), any());
    }

    @Test
    void aRealRunUsesTheCutoffOfEachPeriod() {
        when(retention.run(any(), any(), any())).thenReturn(new RuleRetention.Counts(0, 0, 0, 0, 0, false));

        RetentionJob.Result result = job(true, false, lock).run();

        assertThat(result.status()).isEqualTo(RetentionJob.Status.COMPLETED);
        Instant cutoff = NOW.minus(java.time.Duration.ofDays(30));
        verify(retention).run(cutoff, cutoff, cutoff);
    }

    @Test
    void anotherInstanceHoldingTheLockSkipsTheRun() {
        RetentionJob.Result result = job(true, false, action -> false).run();

        assertThat(result.status()).isEqualTo(RetentionJob.Status.SKIPPED);
        verifyNoInteractions(retention);
    }

    @Test
    void aFailureIsReportedAndNotThrown() {
        when(retention.run(any(), any(), any())).thenThrow(new IllegalStateException("boom"));

        assertThat(job(true, false, lock).run().status()).isEqualTo(RetentionJob.Status.FAILED);
    }

    @Test
    void periodsAreRequiredOnlyWhenEnabled() {
        Supplier<Boolean> off = () -> new RetentionProperties(false, true, "0 25 3 * * *", 10, 10, null, null, null).isPeriodsSetWhenEnabled();
        assertThat(off.get()).isTrue();
        assertThat(new RetentionProperties(true, true, "0 25 3 * * *", 10, 10, P, P, null).isPeriodsSetWhenEnabled()).isFalse();
        assertThat(new RetentionProperties(true, true, "0 25 3 * * *", 10, 10, P, P, Period.ZERO).isPeriodsSetWhenEnabled()).isFalse();
        assertThat(new RetentionProperties(true, true, "0 25 3 * * *", 10, 10, P, P, P).isPeriodsSetWhenEnabled()).isTrue();
    }
}
