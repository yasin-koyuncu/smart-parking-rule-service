package com.parkview.ruleengine.readmodel;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Read-only access to {@link ParkingSpotView}; deliberately not a CrudRepository, so there is no save or delete. */
@Transactional(readOnly = true)
public interface ParkingSpotViewRepository extends Repository<ParkingSpotView, UUID> {

    /** Spots drawn as polygons in the zone; point spots carry no geometry that can be matched against a vehicle. */
    @Query("select s from ParkingSpotView s where s.zoneId = :zoneId and s.shape = 'polygon'")
    List<ParkingSpotView> findPolygonSpotsByZoneId(String zoneId);
}
