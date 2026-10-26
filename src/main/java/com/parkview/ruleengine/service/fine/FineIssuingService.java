package com.parkview.ruleengine.service.fine;

import com.parkview.ruleengine.client.PlateOwnerDirectory;
import com.parkview.ruleengine.config.RuleEngineProperties;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.repository.FineRepository;
import com.parkview.ruleengine.util.Plates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Issues the fine for a violation whose grace period has passed; called by the fine sweep in {@link ViolationJobs}. */
@Slf4j
@Service
@RequiredArgsConstructor
public class FineIssuingService {

    private final FineRepository fines;
    private final PlateOwnerDirectory plateOwners;
    private final FineEvents events;
    private final FineEvidenceService evidence;
    private final RuleEngineProperties properties;
    private final Clock clock;

    /**
     * Creates the fine for a violation that the caller has locked, links it, freezes its evidence and writes
     * {@code fine.issued} to the outbox, all in the surrounding transaction: either the fine, its evidence and its
     * event exist, or none of them.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Fine issueFine(Violation violation) {
        Instant now = clock.instant();
        UUID owner = violation.getUserId() != null
                ? violation.getUserId()
                : plateOwners.ownerOf(violation.getPlate()).orElse(null);

        Fine fine = fines.save(Fine.issue(violation, properties.fineAmountFor(violation.getViolationType()), owner, now));
        violation.markFined(fine.getId(), now);

        evidence.record(fine, violation);
        events.issued(fine, violation, owner, now);
        log.info("Fine {} issued: {} SEK ({}) for plate {} at site {} zone {}", fine.getId(), fine.getAmountSek(),
                fine.getReason(), Plates.mask(fine.getPlate()), fine.getSiteId(), fine.getZoneId());
        return fine;
    }
}
