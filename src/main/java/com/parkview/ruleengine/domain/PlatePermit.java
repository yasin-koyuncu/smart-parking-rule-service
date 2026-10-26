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
 * Grants a plate the right to park in spots that require a given permit type (ADR 0009): in one zone, in the whole of a site
 * (zone id null), or at every site (site id and zone id null). A permit for a zone also names the zone's site. The plate is
 * stored in its normalised form.
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

    /** Null: valid at every site. */
    @Column(name = "site_id")
    private String siteId;

    /** Null: valid in the whole site (or at every site when {@code siteId} is null too). */
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

    /**
     * @param siteId null for a permit valid at every site
     * @param zoneId null for a permit valid in the whole site; a zone needs its site
     */
    public static PlatePermit grant(String plate, String permitType, String siteId, String zoneId,
                                    Instant validFrom, Instant validTo, String createdBy) {
        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw new IllegalArgumentException("validTo must be after validFrom");
        }
        if (zoneId != null && siteId == null) {
            throw new IllegalArgumentException("A permit for a zone names the site of the zone");
        }
        PlatePermit permit = new PlatePermit();
        permit.plate = plate;
        permit.permitType = permitType.trim();
        permit.siteId = siteId;
        permit.zoneId = zoneId;
        permit.validFrom = validFrom;
        permit.validTo = validTo;
        permit.createdBy = createdBy;
        return permit;
    }

    /** Valid at every site: only an administrator grants, sees to and revokes it as such. */
    public boolean isGlobal() {
        return siteId == null && zoneId == null;
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
