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
 * Read-only view of {@code sessions}, which is owned by session-service. rule-service reads the start of the
 * active session ({@code ended_at} is null) of a plate to decide whether it overstays.
 * Maps just the columns used (not {@code spot_id}: the Supabase base table lacks it until session-service
 * has migrated); never written by this service and never created by its Liquibase changelog.
 */
@Entity
@Immutable
@Table(name = "sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SessionView {

    @Id
    private UUID id;

    @Column(name = "plate")
    private String plate;

    @Column(name = "zone_id")
    private String zoneId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof SessionView other && id != null && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return SessionView.class.hashCode();
    }
}
