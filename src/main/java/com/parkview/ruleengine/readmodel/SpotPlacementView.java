package com.parkview.ruleengine.readmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * Where a spot is drawn on a camera view: the {@code view_id} and {@code image_polygon} columns of {@code parking_spots}
 * (added by parking-mapping-service; {@code image_polygon} is in the pixel space of the view's corrected image, the
 * space the {@code vehiclePolygon} of a {@code vehicle.detected} event with that {@code viewId} is in).
 *
 * <p>Deliberately a {@link Subselect} and not a {@code @Table}: schema validation checks the columns of every table
 * mapping at start-up and would refuse to start against a database that has not got the migration yet. A subselect is
 * only run when queried, and {@code JpaSpotDirectory} queries it only after it has seen that the columns exist.
 * Read-only, like every read model.
 */
@Entity
@Immutable
@Subselect("select id, view_id, image_polygon from parking_spots")
@Synchronize("parking_spots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpotPlacementView {

    @Id
    private UUID id;

    @Column(name = "view_id")
    private UUID viewId;

    /** Raw JSON text, rejected per spot by the adapter like {@code ParkingSpotView.coordinates}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "image_polygon")
    private String imagePolygon;

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof SpotPlacementView other && id != null && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return SpotPlacementView.class.hashCode();
    }
}
