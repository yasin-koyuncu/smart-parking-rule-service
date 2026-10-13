package com.parkview.ruleengine.readmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * Read-only view of {@code zones}, which is owned by the Supabase schema / parking-mapping-service.
 * rule-service only needs the street address to enrich {@code violation.created} and {@code fine.issued} events.
 * Maps just the columns used; never written by this service and never created by its Liquibase changelog.
 */
@Entity
@Immutable
@Table(name = "zones")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ZoneView {

    @Id
    private String id;

    @Column(name = "address")
    private String address;

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof ZoneView other && id != null && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return ZoneView.class.hashCode();
    }
}
