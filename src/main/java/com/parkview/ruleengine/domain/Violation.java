package com.parkview.ruleengine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

import com.parkview.ruleengine.domain.type.ResolutionReason;
import com.parkview.ruleengine.domain.type.ViolationType;
/**
 * An open or closed breach of a parking rule by one plate in one spot. At most one <em>open</em>
 * violation exists per plate, spot and type (partial unique index {@code spot_violations_open_uq}). It belongs to the site of
 * the spot, and to its zone only when the spot has one (ADR 0009).
 *
 * <p>Detections never update this entity through dirty checking (that could overwrite a concurrent
 * fine); touching and resolving are atomic statements in {@code ViolationRepository}.
 */
@Entity
@Table(name = "spot_violations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Violation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "spot_id", nullable = false)
    private UUID spotId;

    @Column(name = "site_id", nullable = false)
    private String siteId;

    /** Null for a spot without a zone. */
    @Column(name = "zone_id")
    private String zoneId;

    @Column(nullable = false)
    private String plate;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "violation_type", nullable = false)
    private ViolationType violationType;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "grace_until", nullable = false)
    private Instant graceUntil;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_reason")
    private ResolutionReason resolutionReason;

    @Column(name = "fine_issued_at")
    private Instant fineIssuedAt;

    @Column(name = "fine_id")
    private UUID fineId;

    @Column(name = "image_path")
    private String imagePath;

    @Column(name = "notified_at")
    private Instant notifiedAt;

    /** Plain-language text of the rule that decided this violation when it was opened; null for older rows. */
    @Column(name = "rule_summary", updatable = false)
    private String ruleSummary;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    /** Opens a new violation that is first seen {@code detectedAt} and fined once {@code graceUntil} has passed. */
    public static Violation open(UUID spotId, String siteId, String zoneId, String plate, UUID userId,
                                 ViolationType type, Instant detectedAt, Instant graceUntil) {
        return open(spotId, siteId, zoneId, plate, userId, type, detectedAt, graceUntil, null);
    }

    /** Same, remembering the text of the rule that decided it (frozen into the evidence when the fine is issued). */
    public static Violation open(UUID spotId, String siteId, String zoneId, String plate, UUID userId,
                                 ViolationType type, Instant detectedAt, Instant graceUntil, String ruleSummary) {
        if (siteId == null) {
            throw new IllegalArgumentException("A violation belongs to a site");
        }
        Violation v = new Violation();
        v.ruleSummary = ruleSummary;
        v.spotId = spotId;
        v.siteId = siteId;
        v.zoneId = zoneId;
        v.plate = plate;
        v.userId = userId;
        v.violationType = type;
        v.detectedAt = detectedAt;
        v.lastSeenAt = detectedAt;
        v.graceUntil = graceUntil;
        return v;
    }

    /** Neither resolved nor fined. */
    public boolean isActive() {
        return resolvedAt == null && fineIssuedAt == null;
    }

    /** Records the fine; only an active violation can be fined. */
    public void markFined(UUID fine, Instant at) {
        if (!isActive()) {
            throw new IllegalStateException("Violation " + id + " is not active");
        }
        this.fineId = fine;
        this.fineIssuedAt = at;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Violation other && id != null && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return Violation.class.hashCode();
    }
}
