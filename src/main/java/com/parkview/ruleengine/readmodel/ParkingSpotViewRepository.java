package com.parkview.ruleengine.readmodel;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only access to {@link ParkingSpotView}; deliberately not a CrudRepository, so there is no save or delete. */
@Transactional(readOnly = true)
public interface ParkingSpotViewRepository extends Repository<ParkingSpotView, UUID> {

    /**
     * Spots drawn as polygons at the site, in a zone or not; point spots carry no geometry that can be matched against a
     * vehicle. Only for a database without camera views: it would also return the spots of a view, whose
     * {@code coordinates} are not in the pixel space of a legacy observation.
     */
    @Query("select s from ParkingSpotView s where s.siteId = :siteId and s.shape = 'polygon'")
    List<ParkingSpotView> findPolygonSpotsBySiteId(String siteId);

    /**
     * The legacy spots of a site in a database that has camera views: polygon spots drawn on no view, with their raw
     * camera-pixel {@code coordinates} (which are null for a spot that only has a geo polygon).
     * Needs the {@code view_id} column.
     */
    @Query("""
            select s from ParkingSpotView s
            where s.siteId = :siteId and s.shape = 'polygon'
              and not exists (select 1 from SpotPlacementView p where p.id = s.id and p.viewId is not null)""")
    List<ParkingSpotView> findPolygonSpotsOutsideViews(String siteId);

    /**
     * The spots drawn on a camera view, with their {@code image_polygon}. The shape does not matter here: the polygon
     * of the view is the geometry. The site is a guard so that a view id never leaks spots of another site.
     * Needs the {@code view_id} and {@code image_polygon} columns.
     */
    @Query("""
            select new com.parkview.ruleengine.readmodel.PlacedSpot(s, p.imagePolygon)
            from ParkingSpotView s, SpotPlacementView p
            where p.id = s.id and p.viewId = :viewId and s.siteId = :siteId""")
    List<PlacedSpot> findSpotsOnView(UUID viewId, String siteId);

    Optional<ParkingSpotView> findById(UUID id);
}
