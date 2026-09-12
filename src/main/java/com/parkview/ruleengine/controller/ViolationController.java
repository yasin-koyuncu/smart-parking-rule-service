package com.parkview.ruleengine.controller;

import com.parkview.ruleengine.model.entity.Violation;
import com.parkview.ruleengine.service.ViolationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/violations")
@RequiredArgsConstructor
public class ViolationController {

    private final ViolationService violationService;

    /** Aktiva violations per zon — används av operatörsvyn */
    @GetMapping("/zone/{zoneId}")
    public List<Violation> getByZone(@PathVariable String zoneId) {
        return violationService.getActiveByZone(zoneId);
    }

    /** Violations per registreringsskylt — används av förarappen */
    @GetMapping("/plate/{plate}")
    public List<Violation> getByPlate(@PathVariable String plate) {
        return violationService.getByPlate(plate.toUpperCase());
    }

    /** Markera en violation som löst */
    @PatchMapping("/{id}/resolve")
    public ResponseEntity<Void> resolve(@PathVariable UUID id) {
        violationService.resolve(id);
        return ResponseEntity.noContent().build();
    }
}
