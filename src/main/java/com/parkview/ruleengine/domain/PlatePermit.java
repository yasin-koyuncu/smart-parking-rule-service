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
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Grants a plate the right to park in spots that require a given permit type, either in one zone
 * or (zone id null) in every zone. The plate is stored in its normalised form.
 */
@Entity
@Table(name = "plate_permits")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlatePermit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String plate;

    @Column(name = "permit_type", nullable = false)
    private String permitType;

    @Column(name = "zone_id")
    private String zoneId;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    public static PlatePermit grant(String plate, String permitType, String zoneId,
                                    Instant validFrom, Instant validTo, String createdBy) {
        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw new IllegalArgumentException("validTo must be after validFrom");
        }
        PlatePermit permit = new PlatePermit();
        permit.plate = plate;
        permit.permitType = permitType.trim();
        permit.zoneId = zoneId;
        permit.validFrom = validFrom;
        permit.validTo = validTo;
        permit.createdBy = createdBy;
        return permit;
    }

    public boolean isGlobal() {
        return zoneId == null;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof PlatePermit other && id != null && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return PlatePermit.class.hashCode();
    }
}
