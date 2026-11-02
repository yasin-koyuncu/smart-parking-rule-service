package com.parkview.ruleengine.readmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only view of {@code sessions.v_sessions}, the published view of the sessions of session-service (ADR 0010). rule-service reads the start of the
 * active session ({@code ended_at} is null) of a plate at a site
 * (in a zone, when the spot has one) to decide whether it overstays;
 * a voided session (an operator corrected a wrong one) never happened and is not active.
 * Maps just the columns used; never written by this service and never created by its Liquibase changelog.
 */
@Entity
@Immutable
@Table(schema = "sessions", name = "v_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SessionView {

    @Id
    private UUID id;

    @Column(name = "plate")
    private String plate;

    @Column(name = "site_id")
    private String siteId;

    /** Null for a session at a place of the site that belongs to no zone. */
    @Column(name = "zone_id")
    private String zoneId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof SessionView other && id != null && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return SessionView.class.hashCode();
    }
}
