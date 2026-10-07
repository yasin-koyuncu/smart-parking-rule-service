package com.parkview.ruleengine.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Bot utfärdad för en violation. Tabellen ägs av session-service migrering
 * (den skapades tillsammans med sessions), men rule-service är den som
 * beslutar om och skriver böter — det är den som känner till
 * regelöverträdelsen.
 */
@Entity
@Table(name = "fines")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Fine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String plate;

    @Column(name = "zone_id", nullable = false)
    private String zoneId;

    @Column(name = "camera_id")
    private String cameraId;

    // "no_parking" | "overstay" | "wrong_permit" | "boundary_exceeded"
    @Column(nullable = false)
    private String reason;

    @Column(name = "reason_code")
    private String reasonCode;

    @Column(name = "reason_text")
    private String reasonText;

    @Column(name = "amount_sek", nullable = false)
    private BigDecimal amountSek;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(nullable = false)
    private boolean paid;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "image_path")
    private String imagePath;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
