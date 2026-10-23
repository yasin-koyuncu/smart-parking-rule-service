package com.parkview.ruleengine.client;

import com.parkview.ruleengine.domain.value.ParkingSpot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port: the polygon spots of a zone (owned by parking-mapping-service). Today a read of {@code parking_spots}.
 * A spot is either a legacy spot, polygon in raw camera pixels, or drawn on a camera view, polygon in the pixel space of
 * that view's corrected image. The two sets never overlap.
 */
public interface SpotDirectory {

    /** The legacy spots of a zone: those drawn on no camera view, with their {@code coordinates}. */
    List<ParkingSpot> polygonSpotsOf(String zoneId);

    /**
     * The spots of a camera view of the zone, {@link ParkingSpot#polygon()} being their {@code image_polygon}.
     * Empty when the view has none, and in a database that has no camera views yet.
     */
    List<ParkingSpot> polygonSpotsOfView(String zoneId, UUID viewId);

    /** The label of a spot of either kind. */
    Optional<String> spotNumberOf(UUID spotId);
}
