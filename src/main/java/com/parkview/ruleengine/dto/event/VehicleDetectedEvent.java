package com.parkview.ruleengine.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Consumed: {@code vehicle.detected} on exchange {@code parkview.vehicle}, published by ingestion for every
 * occupied spot (an observation, throttled per spot and plate), not only for spots the detector flagged.
 *
 * @param zoneId         zone of the camera, null for a camera without a zone (ADR 0009)
 * @param plate          recognised plate, null when the camera could not read it
 * @param vehiclePolygon vehicle outline as {@code [[x, y], ...]} in the same coordinate space as the spot polygons: camera
 *                       pixels for a legacy observation, the pixel space of the view's corrected image when {@code viewId} is set
 * @param spotId         spot the camera associated the vehicle with (a hint, the rule service picks the best spot itself)
 * @param timestamp      observation time, epoch milliseconds
 * @param vehicleClass   body type seen by the detector ({@code car|motorcycle|bus|truck}), null when unknown; other values count as unknown
 * @param viewId         camera view the frame was checked against, null for a legacy (view-less) observation. It decides
 *                       which spots the vehicle is matched against: those of the view, never the legacy ones, and the other way round
 * @param siteId         site of the camera; null from a producer that predates sites, then the zone's site is used.
 *                       An observation needs at least one of {@code siteId} and {@code zoneId}
 */
public record VehicleDetectedEvent(
        @NotBlank @Size(max = 64) String cameraId,
        @Size(max = 64) String zoneId,
        @Size(max = 32) String plate,
        @NotNull @Size(min = 3, max = 500) List<@NotNull @Size(min = 2, max = 2) List<@NotNull Double>> vehiclePolygon,
        UUID spotId,
        String spotNumber,
        double iou,
        double confidence,
        long timestamp,
        @Size(max = 32) String vehicleClass,
        UUID viewId,
        @Size(max = 64) String siteId
) {

    /** Same without a site, as published before ADR 0009. */
    public VehicleDetectedEvent(String cameraId, String zoneId, String plate, List<List<Double>> vehiclePolygon, UUID spotId,
                                String spotNumber, double iou, double confidence, long timestamp, String vehicleClass, UUID viewId) {
        this(cameraId, zoneId, plate, vehiclePolygon, spotId, spotNumber, iou, confidence, timestamp, vehicleClass, viewId, null);
    }

    /** An observation must say where it was made: its site, or a zone (whose site is then used). */
    @JsonIgnore
    @AssertTrue(message = "{validation.siteOrZone}")
    public boolean isPlaced() {
        return isSet(siteId) || isSet(zoneId);
    }

    private static boolean isSet(String id) {
        return id != null && !id.isBlank();
    }
}
