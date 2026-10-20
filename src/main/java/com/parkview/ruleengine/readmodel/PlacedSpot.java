package com.parkview.ruleengine.readmodel;

/** A spot together with its polygon on a camera view (raw JSON text of {@code image_polygon}); the result of a view query. */
public record PlacedSpot(ParkingSpotView spot, String imagePolygon) {
}
