package com.parkview.ruleengine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the fine was based on, frozen when it was issued: one row per fine, inserted in the same transaction as the fine and
 * never changed ({@link Immutable}, and a database trigger rejects UPDATE and DELETE). The rule is kept as text
 * ({@code ruleSummary}) so editing the zone rules later does not rewrite history. {@code imagePaths} are storage keys
 * of camera snapshots that can show other people's cars: operators with {@code violation:read} only.
 */
@Entity
@Immutable
@Table(name = "fine_evidence")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FineEvidence {

    public static final String BASIS_AUTOMATIC = "automatic";

    @Id
    @Column(name = "fine_id")
    private UUID fineId;

    @Column(nullable = false)
    private String plate;

    @Column(name = "zone_id", nullable = false)
    private String zoneId;

    @Column(name = "zone_address")
    private String zoneAddress;

    @Column(name = "spot_id")
    private UUID spotId;

    @Column(name = "spot_number")
    private String spotNumber;

    /** Upper-case enum name, like {@code spot_violations.violation_type}. */
    @Column(name = "violation_type", nullable = false)
    private String violationType;

    @Column(name = "violation_id", nullable = false)
    private UUID violationId;

    @Column(name = "rule_summary", nullable = false)
    private String ruleSummary;

    @Column(name = "grace_minutes", nullable = false)
    private int graceMinutes;

    @Column(name = "violation_detected_at", nullable = false)
    private Instant violationDetectedAt;

    @Column(name = "grace_expired_at", nullable = false)
    private Instant graceExpiredAt;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detection_ids", nullable = false, columnDefinition = "jsonb")
    private List<String> detectionIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "image_paths", nullable = false, columnDefinition = "jsonb")
    private List<String> imagePaths;

    @Column(name = "min_confidence", precision = 5, scale = 4)
    private BigDecimal minConfidence;

    @Column(name = "decision_basis", nullable = false)
    private String decisionBasis;

    /** The evidence of {@code fine}, built from the violation it was issued for and the snapshots of the detections involved. */
    public static FineEvidence of(Fine fine, Violation violation, String zoneAddress, String spotNumber, String ruleSummary,
                                  List<String> detectionIds, List<String> imagePaths, BigDecimal minConfidence) {
        FineEvidence evidence = new FineEvidence();
        evidence.fineId = fine.getId();
        evidence.plate = fine.getPlate();
        evidence.zoneId = fine.getZoneId();
        evidence.zoneAddress = zoneAddress;
        evidence.spotId = violation.getSpotId();
        evidence.spotNumber = spotNumber;
        evidence.violationType = violation.getViolationType().name();
        evidence.violationId = violation.getId();
        evidence.ruleSummary = ruleSummary;
        evidence.graceMinutes = (int) Duration.between(violation.getDetectedAt(), violation.getGraceUntil()).toMinutes();
        evidence.violationDetectedAt = violation.getDetectedAt();
        evidence.graceExpiredAt = violation.getGraceUntil();
        evidence.issuedAt = fine.getIssuedAt();
        evidence.detectionIds = List.copyOf(detectionIds);
        evidence.imagePaths = List.copyOf(imagePaths);
        evidence.minConfidence = minConfidence;
        evidence.decisionBasis = BASIS_AUTOMATIC;
        return evidence;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof FineEvidence other && fineId != null && fineId.equals(other.fineId));
    }

    @Override
    public int hashCode() {
        return FineEvidence.class.hashCode();
    }
}
