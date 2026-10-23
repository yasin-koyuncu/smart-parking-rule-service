package com.parkview.ruleengine.service.fine;

import com.parkview.ruleengine.domain.Dispute;
import com.parkview.ruleengine.domain.type.DisputeChannel;
import com.parkview.ruleengine.domain.type.DisputeStatus;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.FineAction;
import com.parkview.ruleengine.domain.type.FineActionType;
import com.parkview.ruleengine.repository.DisputeRepository;
import com.parkview.ruleengine.repository.FineActionRepository;
import com.parkview.ruleengine.repository.FineRepository;
import com.parkview.ruleengine.web.ConflictException;
import com.parkview.ruleengine.web.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The commands of a dispute: open it, withdraw it, decide it. Humans must always be able to review an automated fine:
 * while a dispute is open the fine cannot be paid, and an operator ends it by upholding or voiding the fine.
 *
 * <p>Every command locks the fine row first (the same lock as void, mark-paid and the online payment) and only then reads
 * the dispute, so none of them interleave. Each writes its {@code fine_actions} row and its outbox event in the same
 * transaction. Who may call a command is decided by the controller; this class decides on the state. The message and
 * the decision note are personal text: stored, shown to the driver and staff, never logged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisputeService {

    private final FineRepository fines;
    private final DisputeRepository disputes;
    private final FineActionRepository actions;
    private final FineManagementService fineManagement;
    private final DisputeCloser closer;
    private final FineEvents events;
    private final Clock clock;

    /**
     * Opens a dispute for a fine that is neither paid nor voided.
     *
     * @param by      the user who asks (the driver, or the operator who took the call), null when the token carries no user id
     * @param message the reason given, already trimmed and length checked
     * @throws ResourceNotFoundException when the fine is unknown
     * @throws ConflictException         when the fine is paid or voided, already has an open dispute, or a person has already upheld it
     */
    @Transactional
    public Dispute open(UUID fineId, DisputeChannel channel, UUID by, String message) {
        Fine fine = lockFine(fineId);
        if (fine.isPaid()) {
            throw new ConflictException("This fine has already been paid, so it can no longer be disputed.");
        }
        if (fine.isVoided()) {
            throw new ConflictException("This fine has been cancelled, so there is nothing to dispute.");
        }
        if (disputes.existsByFineIdAndStatus(fineId, DisputeStatus.OPEN)) {
            throw new ConflictException("A review of this fine has already been requested and is still open.");
        }
        if (disputes.existsByFineIdAndStatus(fineId, DisputeStatus.UPHELD)) {
            throw new ConflictException("This fine has already been reviewed by an operator and the fine stands.");
        }
        Instant now = clock.instant();
        Dispute dispute = disputes.saveAndFlush(Dispute.open(fine, by, channel, message, now));
        actions.save(FineAction.of(fineId, now, by, FineActionType.DISPUTE_OPENED, message,
                Map.of("disputeId", dispute.getId().toString(), "channel", channel.wire())));
        events.disputed(dispute, now);
        log.info("Dispute {} opened for fine {} ({})", dispute.getId(), fineId, channel.wire());
        return dispute;
    }

    /**
     * The driver takes an open dispute back.
     *
     * @throws ResourceNotFoundException when the dispute is unknown
     * @throws ConflictException         when it is not open any more
     */
    @Transactional
    public Dispute withdraw(UUID disputeId, UUID by) {
        Fine fine = lockFine(fineOf(disputeId));
        Dispute dispute = openDispute(disputeId);
        closer.close(dispute, fine, DisputeStatus.WITHDRAWN, by, null, clock.instant());
        return dispute;
    }

    /**
     * An operator decides an open dispute. {@code UPHELD} closes it and releases the payment hold; {@code VOIDED} voids the fine
     * through {@link FineManagementService#voidFine} (same audit row, same {@code fine.voided} event, the note is the void
     * reason), which also closes the dispute.
     *
     * @param outcome {@code UPHELD} or {@code VOIDED}
     * @param note    the written reason, already trimmed and length checked
     * @throws ResourceNotFoundException when the dispute is unknown
     * @throws ConflictException         when it was decided or withdrawn already
     */
    @Transactional
    public Dispute decide(UUID disputeId, DisputeStatus outcome, UUID actor, String note) {
        if (outcome != DisputeStatus.UPHELD && outcome != DisputeStatus.VOIDED) {
            throw new IllegalArgumentException("an operator decides upheld or voided, not " + outcome);
        }
        Fine fine = lockFine(fineOf(disputeId));
        Dispute dispute = openDispute(disputeId);
        if (outcome == DisputeStatus.VOIDED) {
            fineManagement.voidFine(fine.getId(), note, actor);
        } else {
            closer.close(dispute, fine, outcome, actor, note, clock.instant());
        }
        return dispute;
    }

    private UUID fineOf(UUID disputeId) {
        return disputes.findFineIdById(disputeId).orElseThrow(() -> new ResourceNotFoundException("Dispute", disputeId));
    }

    private Fine lockFine(UUID fineId) {
        return fines.findByIdForUpdate(fineId).orElseThrow(() -> new ResourceNotFoundException("Fine", fineId));
    }

    /** Loaded after the fine lock, so the state is current; a closed dispute is final. */
    private Dispute openDispute(UUID disputeId) {
        Dispute dispute = disputes.findById(disputeId).orElseThrow(() -> new ResourceNotFoundException("Dispute", disputeId));
        if (!dispute.isOpen()) {
            throw new ConflictException(dispute.getStatus() == DisputeStatus.WITHDRAWN
                    ? "This dispute has been withdrawn and cannot be changed."
                    : "This dispute has already been decided and cannot be changed.");
        }
        return dispute;
    }
}
