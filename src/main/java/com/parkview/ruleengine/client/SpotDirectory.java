package com.parkview.ruleengine.client;

import com.parkview.ruleengine.domain.ParkingSpot;

import java.util.List;

/** Port: the polygon spots of a zone (owned by parking-mapping-service). Today a read of {@code parking_spots}. */
public interface SpotDirectory {

    List<ParkingSpot> polygonSpotsOf(String zoneId);
}
