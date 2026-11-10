package com.parkview.ruleengine.service.violation;

import com.parkview.ruleengine.config.RuleEngineProperties;
import com.parkview.ruleengine.repository.ViolationRepository;
import com.parkview.ruleengine.service.fine.FineIssuingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Scheduled maintenance of violations. Both jobs are safe to run on several instances at once.
 *
 * <ul>
 *   <li>fine sweep: fines violations whose grace period has passed. Each violation is claimed with
 *       {@code FOR UPDATE SKIP LOCKED} and fined in its own transaction, so a failure affects only that
 *       violation and two instances never fine the same one. A violation is only fined when a detection confirmed it
 *       within {@code fine-freshness-seconds}: rule-service is not told that a car left, so a violation that stopped being
 *       confirmed (the car drove away during the grace period) must not be fined just because its grace is over. The
 *       stale sweep, or {@code session.ended}, closes it. The database allows one fine per violation: should a second fine ever be
 *       attempted, its transaction fails on the unique constraint, rolls back as a whole and is logged as already fined.
 *       A violation whose fine cannot be issued (site without currency, no amount configured) gets {@code fining_retry_at = now +
 *       fining-retry-delay} in the same transaction and is not a candidate before then: it cannot fill the head of the batch and starve the
 *       others, and its ERROR is logged once per delay instead of once per sweep.</li>
 *   <li>stale sweep: resolves violations no detection has confirmed for {@code stale-minutes} as LEFT.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ViolationJobs {

    private final ViolationRepository violations;
    private final ViolationService violationService;
    private final FineIssuingService fineIssuing;
    private final RuleEngineProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;

    @Scheduled(fixedDelayString = "${parkview.rule-engine.expire-sweep-ms:60000}")
    public void issueDueFines() {
        BatchResult batch;
        do {
            batch = issueBatch();
            // a full batch may be followed by more due violations; stop when nothing moved (fined or deferred), which also ends the loop for
            // violations that fail or are locked by another instance and would otherwise be met again at once
        } while (batch.candidates() == properties.expireBatchSize() && batch.fined() + batch.deferred() > 0);
    }

    @Scheduled(fixedDelayString = "${parkview.rule-engine.stale-sweep-ms:60000}")
    public void resolveStaleViolations() {
        int resolved = violationService.resolveStale();
        if (resolved > 0) {
            log.info("Stale sweep closed {} violations", resolved);
        }
    }

    /** One batch of the fine sweep. */
    BatchResult issueBatch() {
        Instant now = clock.instant();
        Instant seenSince = now.minus(properties.fineFreshness());
        List<UUID> due = violations.findDueForFine(now, seenSince, PageRequest.of(0, properties.expireBatchSize()));

        int fined = 0;
        int deferred = 0;
        for (UUID id : due) {
            try {
                FineOutcome outcome = tx.execute(status -> fineOne(id, now, seenSince));
                if (outcome == FineOutcome.FINED) {
                    fined++;
                } else if (outcome == FineOutcome.DEFERRED) {
                    deferred++;
                }
            } catch (DataIntegrityViolationException e) {
                // the database allows one fine per violation (unique violation_id on the evidence, unique fine_id on the violation): a
                // second fine for the same violation is rolled back as a whole and counts as already fined, not as a failure
                log.warn("Violation {} was fined already, no second fine was issued", id);
            } catch (RuntimeException e) {
                log.error("Could not issue a fine for violation {}; it is retried on the next sweep", id, e);
            }
        }
        if (!due.isEmpty()) {
            log.info("Fine sweep: {} due, {} fined, {} deferred", due.size(), fined, deferred);
        }
        return new BatchResult(due.size(), fined, deferred);
    }

    private FineOutcome fineOne(UUID id, Instant now, Instant seenSince) {
        return violations.claimDue(id, now, seenSince)
                .map(violation -> {
                    // empty when the fine cannot be issued (site without currency, no amount for it); the error is logged by the issuing service.
                    // The violation stays open and is left out of the sweep until fining-retry-delay has passed (same transaction as the claim),
                    // so it neither starves the other violations nor logs an error on every sweep; once the configuration is fixed it is
                    // fined at its next retry
                    if (fineIssuing.issueFine(violation).isPresent()) {
                        return FineOutcome.FINED;
                    }
                    violations.deferFining(id, now.plus(properties.finingRetryDelay()));
                    return FineOutcome.DEFERRED;
                })
                .orElse(FineOutcome.SKIPPED);
    }

    private enum FineOutcome {
        FINED, DEFERRED, SKIPPED
    }

    record BatchResult(int candidates, int fined, int deferred) {
    }
}
