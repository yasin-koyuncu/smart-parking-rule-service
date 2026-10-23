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

import com.parkview.ruleengine.domain.converter.LowercaseViolationTypeConverter;
import com.parkview.ruleengine.domain.type.PaidMethod;
import com.parkview.ruleengine.domain.type.ViolationType;
/**
 * A fine issued for a violation. The table is shared with other services (payment reads it through
 * the internal API, the Supabase migrations created its original shape); this service is the only
 * writer of new rows. A fine ends either paid (payment event or an operator) or voided (an operator), never both.
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

    /** Read only: the sweep does not know the camera; rows created elsewhere (older shapes of the table) may carry one. */
    @Column(name = "camera_id", insertable = false, updatable = false)
    private String cameraId;

    @Convert(converter = LowercaseViolationTypeConverter.class)
    @Column(nullable = false)
    private ViolationType reason;

    /** Read only, same reason as {@code cameraId}: optional display data written by other producers of the table. */
    @Column(name = "reason_code", insertable = false, updatable = false)
    private String reasonCode;

    @Column(name = "reason_text", insertable = false, updatable = false)
    private String reasonText;

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

    /** Null for fines paid before the column existed. */
    @Column(name = "paid_at")
    private Instant paidAt;

    /** {@code online | cash | transfer | other}; null while unpaid and for fines paid before the column existed. */
    @Column(name = "paid_method")
    private String paidMethod;

    /** The operator who marked the fine as paid; null for online payments. */
    @Column(name = "paid_by")
    private UUID paidBy;

    @Column(name = "voided_at")
    private Instant voidedAt;

    /** Operator free text: shown in the audit trail to staff only, never logged. */
    @Column(name = "void_reason")
    private String voidReason;

    @Column(name = "voided_by")
    private UUID voidedBy;

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

    public boolean isVoided() {
        return voidedAt != null;
    }

    /**
     * Records the payment. Idempotent: an already paid fine is left untouched and {@code false} is returned.
     *
     * @param by the operator, or null for an online payment
     * @throws IllegalStateException when the fine is voided (callers check first and report a conflict)
     */
    public boolean markPaid(Instant at, PaidMethod method, UUID by) {
        if (paid) {
            return false;
        }
        if (isVoided()) {
            throw new IllegalStateException("A voided fine cannot be paid");
        }
        paid = true;
        paidAt = at;
        paidMethod = method.wire();
        paidBy = by;
        return true;
    }

    /**
     * Voids the fine. Idempotent: an already voided fine is left untouched and {@code false} is returned.
     *
     * @throws IllegalStateException when the fine is paid (a refund is a separate flow)
     */
    public boolean voidFine(Instant at, String reason, UUID by) {
        if (isVoided()) {
            return false;
        }
        if (paid) {
            throw new IllegalStateException("A paid fine cannot be voided");
        }
        voidedAt = at;
        voidReason = reason;
        voidedBy = by;
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
