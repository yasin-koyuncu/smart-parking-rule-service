package com.parkview.ruleengine.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class BoundaryCheckResult {
    private boolean valid;
    private double  iou;
    private double  containment;
    private ViolationType violation;   // null om giltig parkering
    private String  spotId;
    private String  zoneId;
    private String  plate;
}