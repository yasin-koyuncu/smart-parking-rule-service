package com.parkview.ruleengine.model.entity;

import com.parkview.ruleengine.model.ViolationType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "spot_violations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Violation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "spot_id", nullable = false)
    private UUID spotId;

    @Column(name = "zone_id", nullable = false)
    private String zoneId;

    @Column(nullable = false)
    private String plate;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "violation_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private ViolationType violationType;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "grace_until", nullable = false)
    private Instant graceUntil;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "fine_issued_at")
    private Instant fineIssuedAt;

    @Column(name = "fine_id")
    private UUID fineId;

    @Column(name = "image_path")
    private String imagePath;

    @Column(name = "notified_at")
    private Instant notifiedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    public boolean isActive() {
        return resolvedAt == null && fineIssuedAt == null;
    }

    public boolean isGraceExpired() {
        return isActive() && Instant.now().isAfter(graceUntil);
    }
}