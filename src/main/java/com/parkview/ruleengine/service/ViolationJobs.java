package com.parkview.ruleengine.service;

import com.parkview.ruleengine.config.RuleEngineProperties;
import com.parkview.ruleengine.repository.ViolationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 *       violation and two instances never fine the same one. Violations that were not re-confirmed for
 *       {@code stale-minutes} are not fined (the vehicle is gone, the stale sweep closes them).</li>
 *   <li>stale sweep: resolves violations no detection has confirmed for {@code stale-minutes} as LEFT.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ViolationJobs {

    private final ViolationRepository violations;
    private final ViolationService violationService;
    private final FineService fineService;
    private final RuleEngineProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;

    @Scheduled(fixedDelayString = "${parkview.rule-engine.expire-sweep-ms:60000}")
    public void issueDueFines() {
        BatchResult batch;
        do {
            batch = issueBatch();
        } while (batch.candidates() == properties.expireBatchSize() && batch.fined() > 0);
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
        Instant seenSince = now.minus(properties.staleAfter());
        List<UUID> due = violations.findDueForFine(now, seenSince, PageRequest.of(0, properties.expireBatchSize()));

        int fined = 0;
        for (UUID id : due) {
            try {
                if (Boolean.TRUE.equals(tx.execute(status -> fineOne(id, now, seenSince)))) {
                    fined++;
                }
            } catch (RuntimeException e) {
                log.error("Could not issue a fine for violation {}; it is retried on the next sweep", id, e);
            }
        }
        if (!due.isEmpty()) {
            log.info("Fine sweep: {} due, {} fined", due.size(), fined);
        }
        return new BatchResult(due.size(), fined);
    }

    private boolean fineOne(UUID id, Instant now, Instant seenSince) {
        return violations.claimDue(id, now, seenSince)
                .map(violation -> {
                    fineService.issueFine(violation);
                    return true;
                })
                .orElse(false);
    }

    record BatchResult(int candidates, int fined) {
    }
}
