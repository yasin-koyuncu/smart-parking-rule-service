package com.parkview.ruleengine.dto.event;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Consumed: {@code vehicle.detected} on exchange {@code parkview.vehicle}, published by ingestion.
 *
 * @param plate          recognised plate, null when the camera could not read it
 * @param vehiclePolygon vehicle outline as {@code [[x, y], ...]} in the same coordinate space as the spot polygons
 * @param spotId         spot the camera associated the vehicle with (a hint, the rule service picks the best spot itself)
 * @param timestamp      detection time, epoch milliseconds
 */
public record VehicleDetectedEvent(
        @NotBlank @Size(max = 64) String cameraId,
        @NotBlank @Size(max = 64) String zoneId,
        @Size(max = 32) String plate,
        @NotNull @Size(min = 3, max = 500) List<@NotNull @Size(min = 2, max = 2) List<@NotNull Double>> vehiclePolygon,
        UUID spotId,
        String spotNumber,
        double iou,
        double confidence,
        long timestamp
) {
}
