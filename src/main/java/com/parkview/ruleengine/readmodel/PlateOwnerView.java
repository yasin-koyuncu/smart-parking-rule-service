package com.parkview.ruleengine.readmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.util.UUID;

/**
 * Read-only view of {@code user_plates}, which is owned by the Supabase schema (plates registered by drivers).
 * rule-service reads it to attribute a violation to the driver who registered the plate.
 * Maps just the columns used; never written by this service and never created by its Liquibase changelog.
 */
@Entity
@Immutable
@Table(name = "user_plates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlateOwnerView {

    @Id
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "plate")
    private String plate;

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof PlateOwnerView other && id != null && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return PlateOwnerView.class.hashCode();
    }
}
