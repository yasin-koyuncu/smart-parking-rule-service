package com.parkview.ruleengine.readmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only view of {@code detections}, which is owned by ingestion-service (ADR 0001). rule-service reads which camera
 * snapshots belong to a fine when it freezes the evidence: the plate (or the reviewer's correction), the zone, the time,
 * the snapshot key, the detector confidence and the review state. Maps just the columns used; never written by this
 * service and never created by its Liquibase changelog.
 */
@Entity
@Immutable
@Table(name = "detections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DetectionView {

    @Id
    private UUID id;

    @Column(name = "plate")
    private String plate;

    @Column(name = "corrected_plate")
    private String correctedPlate;

    @Column(name = "zone_id")
    private String zoneId;

    @Column(name = "image_path")
    private String imagePath;

    @Column(name = "confidence")
    private BigDecimal confidence;

    /** {@code auto | pending | approved | rejected}. */
    @Column(name = "review_status")
    private String reviewStatus;

    @Column(name = "at")
    private Instant at;

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof DetectionView other && id != null && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return DetectionView.class.hashCode();
    }
}
