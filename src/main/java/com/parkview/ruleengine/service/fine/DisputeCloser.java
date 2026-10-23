package com.parkview.ruleengine.service.fine;

import com.parkview.ruleengine.domain.Dispute;
import com.parkview.ruleengine.domain.type.DisputeStatus;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.FineAction;
import com.parkview.ruleengine.domain.type.FineActionType;
import com.parkview.ruleengine.repository.DisputeRepository;
import com.parkview.ruleengine.repository.FineActionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The one place that ends a dispute: sets the final status, writes the {@code fine_actions} row and publishes
 * {@code fine.dispute.decided}. Used by the dispute commands, by the void of a fine (an open dispute ends as voided) and by
 * the online payment (an open dispute ends as upheld). The caller holds the lock of the fine row.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class DisputeCloser {

    private final DisputeRepository disputes;
    private final FineActionRepository actions;
    private final FineEvents events;

    /** Ends the open dispute of the fine, if there is one, and returns it. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Dispute> closeOpen(Fine fine, DisputeStatus outcome, UUID by, String note, Instant now) {
        Optional<Dispute> open = disputes.findByFineIdAndStatus(fine.getId(), DisputeStatus.OPEN);
        open.ifPresent(dispute -> close(dispute, fine, outcome, by, note, now));
        return open;
    }

    /**
     * @param by   the operator who decided or the driver who withdrew; null when the system closes it (fine paid meanwhile)
     * @param note operator free text, or the system note; null for a withdrawal
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void close(Dispute dispute, Fine fine, DisputeStatus outcome, UUID by, String note, Instant now) {
        dispute.close(outcome, now, by, note);
        actions.save(FineAction.of(fine.getId(), now, by, actionOf(outcome), note,
                Map.of("disputeId", dispute.getId().toString())));
        events.disputeDecided(dispute, fine, now);
        log.info("Dispute {} of fine {} closed: {}", dispute.getId(), fine.getId(), outcome.wire());
    }

    private static FineActionType actionOf(DisputeStatus outcome) {
        return switch (outcome) {
            case UPHELD -> FineActionType.DISPUTE_UPHELD;
            case VOIDED -> FineActionType.DISPUTE_VOIDED;
            case WITHDRAWN -> FineActionType.DISPUTE_WITHDRAWN;
            case OPEN -> throw new IllegalArgumentException("a dispute is not closed with status open");
        };
    }
}
