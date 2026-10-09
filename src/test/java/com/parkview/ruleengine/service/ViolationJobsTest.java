package com.parkview.ruleengine.service;

import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.ViolationType;
import com.parkview.ruleengine.repository.ViolationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ViolationJobsTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");
    private static final Instant SEEN_SINCE = NOW.minus(Duration.ofMinutes(15));

    private final ViolationRepository violations = mock(ViolationRepository.class);
    private final ViolationService violationService = mock(ViolationService.class);
    private final FineService fineService = mock(FineService.class);
    private ViolationJobs jobs;

    @BeforeEach
    void setUp() {
        jobs = new ViolationJobs(violations, violationService, fineService, TestFixtures.properties(),
                Clock.fixed(NOW, ZoneOffset.UTC), TestTransactions.template());
    }

    private Violation due() {
        Violation v = TestFixtures.violation(ViolationType.WRONG_PERMIT, NOW.minusSeconds(3600), NOW.minusSeconds(60));
        when(violations.claimDue(v.getId(), NOW, SEEN_SINCE)).thenReturn(Optional.of(v));
        return v;
    }

    @Test
    void everyClaimedDueViolationIsFined() {
        Violation a = due();
        Violation b = due();
        when(violations.findDueForFine(any(), any(), any(Pageable.class))).thenReturn(List.of(a.getId(), b.getId()));

        ViolationJobs.BatchResult result = jobs.issueBatch();

        assertThat(result).isEqualTo(new ViolationJobs.BatchResult(2, 2));
        verify(fineService).issueFine(a);
        verify(fineService).issueFine(b);
    }

    @Test
    void theDueQueryUsesTheFreshnessWindowAndTheConfiguredBatchSize() {
        when(violations.findDueForFine(any(), any(), any(Pageable.class))).thenReturn(List.of());

        jobs.issueBatch();

        verify(violations).findDueForFine(eq(NOW), eq(SEEN_SINCE), argThat((Pageable p) -> p.getPageSize() == 20));
    }

    @Test
    void aViolationLockedByAnotherInstanceIsSkipped() {
        UUID locked = UUID.randomUUID();
        when(violations.findDueForFine(any(), any(), any(Pageable.class))).thenReturn(List.of(locked));
        when(violations.claimDue(locked, NOW, SEEN_SINCE)).thenReturn(Optional.empty());

        ViolationJobs.BatchResult result = jobs.issueBatch();

        assertThat(result.fined()).isZero();
        verify(fineService, never()).issueFine(any());
    }

    @Test
    void oneFailingViolationDoesNotBlockTheOthers() {
        Violation failing = due();
        Violation healthy = due();
        when(violations.findDueForFine(any(), any(), any(Pageable.class))).thenReturn(List.of(failing.getId(), healthy.getId()));
        doThrow(new IllegalStateException("boom")).when(fineService).issueFine(failing);

        ViolationJobs.BatchResult result = jobs.issueBatch();

        assertThat(result).isEqualTo(new ViolationJobs.BatchResult(2, 1));
        verify(fineService).issueFine(healthy);
    }

    @Test
    void sweepStopsWhenABatchIsNotFull() {
        Violation a = due();
        when(violations.findDueForFine(any(), any(), any(Pageable.class))).thenReturn(List.of(a.getId()));

        jobs.issueDueFines();

        verify(violations).findDueForFine(any(), any(), any(Pageable.class));
    }

    @Test
    void staleSweepDelegatesToTheViolationService() {
        when(violationService.resolveStale()).thenReturn(3);

        jobs.resolveStaleViolations();

        verify(violationService).resolveStale();
    }
}
