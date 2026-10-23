package com.parkview.ruleengine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.parkview.ruleengine.domain.type.FineActionType;
/**
 * One entry of the audit trail of a fine: an operator voided it or marked it as paid, or payment-service paid it.
 * Written in the same transaction as the change itself and never updated. {@code reason} is operator free text
 * (null for online payments), so it is returned to staff only and never logged; {@code details} holds structured,
 * non-personal facts (payment method, payment id).
 */
@Entity
@Table(name = "fine_actions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FineAction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "fine_id", nullable = false, updatable = false)
    private UUID fineId;

    @Column(nullable = false, updatable = false)
    private Instant at;

    /** The operator's user id; null when the system acted (online payment). */
    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(nullable = false, updatable = false)
    private String action;

    @Column(updatable = false)
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> details;

    public static FineAction of(UUID fineId, Instant at, UUID actorId, FineActionType type, String reason,
                                Map<String, Object> details) {
        FineAction entry = new FineAction();
        entry.fineId = fineId;
        entry.at = at;
        entry.actorId = actorId;
        entry.action = type.wire();
        entry.reason = reason;
        entry.details = details == null ? Map.of() : Map.copyOf(details);
        return entry;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof FineAction other && id != null && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return FineAction.class.hashCode();
    }
}
