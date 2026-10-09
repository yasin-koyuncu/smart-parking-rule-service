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
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * A fine issued for a violation. The table is shared with other services (payment reads it through
 * the internal API, the Supabase migrations created its original shape); this service is the only
 * writer of new rows and flips {@code paid} when the payment event arrives.
 */
@Entity
@Table(name = "fines")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Fine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String plate;

    @Column(name = "zone_id", nullable = false)
    private String zoneId;

    @Convert(converter = LowercaseViolationTypeConverter.class)
    @Column(nullable = false)
    private ViolationType reason;

    @Column(name = "amount_sek", nullable = false, precision = 10, scale = 2)
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

    public static Fine issue(Violation violation, BigDecimal amountSek, UUID userId, Instant issuedAt) {
        Fine fine = new Fine();
        fine.plate = violation.getPlate();
        fine.zoneId = violation.getZoneId();
        fine.reason = violation.getViolationType();
        fine.amountSek = amountSek.setScale(2, RoundingMode.HALF_UP);
        fine.issuedAt = issuedAt;
        fine.paid = false;
        fine.userId = userId;
        fine.imagePath = violation.getImagePath();
        return fine;
    }

    /** Idempotent: a redelivered payment event leaves an already paid fine untouched. */
    public boolean markPaid() {
        if (paid) {
            return false;
        }
        paid = true;
        return true;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Fine other && id != null && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return Fine.class.hashCode();
    }
}
