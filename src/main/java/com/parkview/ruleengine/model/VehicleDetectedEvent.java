package com.parkview.ruleengine.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.UUID;

// ── Inkommande event från edge-enheten ────────────────────────────────────────

@Data
public class VehicleDetectedEvent {
    @NotBlank  private String cameraId;
    @NotBlank  private String zoneId;
    private String plate;
    @NotNull   private List<List<Double>> vehiclePolygon;
    private UUID   spotId;
    private String spotNumber;
    private double iou;
    private double confidence;
    private long   timestamp;
}