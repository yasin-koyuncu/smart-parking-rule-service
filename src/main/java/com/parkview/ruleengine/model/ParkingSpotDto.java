package com.parkview.ruleengine.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.UUID;

// ── ParkingSpot (läst från databasen) ─────────────────────────────────────────

@Data
public class ParkingSpotDto {
    private UUID   id;
    private String zoneId;
    private String spotNumber;
    private String type;
    private String permitType;
    private List<List<Double>> polygon;
    private int    boundaryGraceMin;
    private int    permitGraceMin;
}