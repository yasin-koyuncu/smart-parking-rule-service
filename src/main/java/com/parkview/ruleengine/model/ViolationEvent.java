package com.parkview.ruleengine.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class ViolationEvent {
    private UUID   violationId;
    private String plate;
    private String zoneId;
    private String zoneAddress;
    private String userId;
    private UUID   spotId;
    private String spotNumber;
    private ViolationType violationType;
    private int    graceMinutes;
    private long   timestamp;
}