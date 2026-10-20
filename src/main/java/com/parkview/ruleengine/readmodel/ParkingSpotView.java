package com.parkview.ruleengine.readmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * Read-only view of {@code parking_spots}, which is owned by parking-mapping-service.
 * rule-service reads the spot geometry and grace settings to decide which spot a detected vehicle occupies.
 * Maps just the columns used; never written by this service and never created by its Liquibase changelog.
 * {@code coordinates} is kept as the raw JSON text so that unusable geometry is rejected per spot by the
 * adapter instead of failing the whole query; it is null for a spot that only has a geo polygon. The camera-view columns
 * ({@code view_id}, {@code image_polygon}) are deliberately not mapped here, see {@link SpotPlacementView}.
 */
@Entity
@Immutable
@Table(name = "parking_spots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ParkingSpotView {

    @Id
    private UUID id;

    @Column(name = "zone_id")
    private String zoneId;

    @Column(name = "spot_number")
    private String spotNumber;

    @Column(name = "type")
    private String type;

    @Column(name = "shape")
    private String shape;

    @Column(name = "permit_type")
    private String permitType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "coordinates")
    private String coordinates;

    @Column(name = "boundary_grace_min")
    private Integer boundaryGraceMin;

    @Column(name = "permit_grace_min")
    private Integer permitGraceMin;

    @Column(name = "max_minutes")
    private Integer maxMinutes;

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof ParkingSpotView other && id != null && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return ParkingSpotView.class.hashCode();
    }
}
