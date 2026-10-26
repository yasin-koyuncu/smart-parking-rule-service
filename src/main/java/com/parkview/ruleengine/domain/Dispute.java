package com.parkview.ruleengine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

import com.parkview.ruleengine.domain.converter.DisputeChannelConverter;
import com.parkview.ruleengine.domain.converter.DisputeStatusConverter;
import com.parkview.ruleengine.domain.type.DisputeChannel;
import com.parkview.ruleengine.domain.type.DisputeStatus;
/**
 * A request to have a fine reviewed by a person. At most one dispute per fine is open (partial unique index
 * {@code fine_disputes_one_open_uq}); every other status is final. {@code message} and {@code decisionNote} are
 * personal free text: returned to the driver and to staff, never logged and never part of an event. The commands
 * that change a dispute lock the fine row first (see {@code DisputeService}), so there is no version column.
 */
@Entity
@Table(name = "fine_disputes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Dispute {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "fine_id", nullable = false, updatable = false)
    private UUID fineId;

    @Column(nullable = false, updatable = false)
    private String plate;

    @Column(name = "site_id", nullable = false, updatable = false)
    private String siteId;

    /** Null for a fine at a spot without a zone. */
    @Column(name = "zone_id", updatable = false)
    private String zoneId;

    @Column(name = "opened_by_user_id", updatable = false)
    private UUID openedByUserId;

    @Convert(converter = DisputeChannelConverter.class)
    @Column(nullable = false, updatable = false)
    private DisputeChannel channel;

    @Column(nullable = false, updatable = false)
    private String message;

    @Convert(converter = DisputeStatusConverter.class)
    @Column(nullable = false)
    private DisputeStatus status;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    /** The operator who decided, or the driver who withdrew; null while open and when the system closed it (fine paid meanwhile). */
    @Column(name = "decided_by_user_id")
    private UUID decidedByUserId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note")
    private String decisionNote;

    public static Dispute open(Fine fine, UUID openedBy, DisputeChannel channel, String message, Instant at) {
        Dispute dispute = new Dispute();
        dispute.fineId = fine.getId();
        dispute.plate = fine.getPlate();
        dispute.siteId = fine.getSiteId();
        dispute.zoneId = fine.getZoneId();
        dispute.openedByUserId = openedBy;
        dispute.channel = channel;
        dispute.message = message;
        dispute.status = DisputeStatus.OPEN;
        dispute.openedAt = at;
        return dispute;
    }

    public boolean isOpen() {
        return status.isOpen();
    }

    /**
     * Closes an open dispute with its final status.
     *
     * @param by   the deciding operator or the withdrawing driver, null when the system closes it
     * @param note operator free text (or the system note), null for a withdrawal
     * @throws IllegalStateException when the dispute is not open or the outcome is {@code OPEN}
     */
    public void close(DisputeStatus outcome, Instant at, UUID by, String note) {
        if (!isOpen() || outcome.isOpen()) {
            throw new IllegalStateException("Dispute " + id + " cannot move from " + status + " to " + outcome);
        }
        status = outcome;
        decidedAt = at;
        decidedByUserId = by;
        decisionNote = note;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Dispute other && id != null && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return Dispute.class.hashCode();
    }
}
