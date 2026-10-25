package com.parkview.ruleengine.controller;

import com.parkview.ruleengine.dto.response.ViolationResponse;
import com.parkview.ruleengine.mapper.ViolationMapper;
import com.parkview.ruleengine.security.AccessPolicy;
import com.parkview.ruleengine.security.AuthenticatedUser;
import com.parkview.ruleengine.security.CurrentUser;
import com.parkview.ruleengine.util.Plates;
import com.parkview.ruleengine.service.violation.ViolationService;
import com.parkview.ruleengine.web.PagedResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/violations")
@RequiredArgsConstructor
@Tag(name = "Violations", description = "Parking violations detected by the rule engine")
public class ViolationController {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "detectedAt").and(Sort.by("id"));
    /** The permission that makes the camera snapshot key of a violation visible; drivers never have it (ADR 0008). */
    private static final String EVIDENCE_PERMISSION = "violation:read";

    private final ViolationService violationService;
    private final CurrentUser currentUser;
    private final AccessPolicy accessPolicy;

    @GetMapping("/zone/{zoneId}")
    @PreAuthorize("hasAuthority('violation:read')")
    @Operation(summary = "Active violations of a zone",
            description = "Violations that are neither resolved nor fined, newest first. Needs permission violation:read and, for operators, the zone in their zones claim.")
    @ApiResponse(responseCode = "200", description = "A page of violations; the total is in the X-Total-Count header")
    @ApiResponse(responseCode = "403", description = "Not an operator of this zone")
    public ResponseEntity<List<ViolationResponse>> getByZone(
            @PathVariable String zoneId,
            @Parameter(description = "Zero-based page") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, at most 500") @RequestParam(defaultValue = "100") int size) {
        accessPolicy.requireZone(currentUser.require(), zoneId);
        return PagedResponses.of(violationService
                .activeByZone(zoneId, PagedResponses.pageable(page, size, NEWEST_FIRST))
                .map(v -> ViolationMapper.toResponse(v, true)));
    }

    @GetMapping("/plate/{plate}")
    @PreAuthorize("hasRole('DRIVER') or hasAuthority('violation:read')")
    @Operation(summary = "All violations of a plate",
            description = "Newest first. Drivers may only query plates in their plates claim; operators and administrators holding "
                    + "violation:read any plate, an operator seeing only the violations in their zones. Only callers with violation:read get imagePath.")
    @ApiResponse(responseCode = "200", description = "A page of violations; the total is in the X-Total-Count header")
    @ApiResponse(responseCode = "403", description = "A driver asked for a plate that is not theirs")
    public ResponseEntity<List<ViolationResponse>> getByPlate(
            @PathVariable String plate,
            @Parameter(description = "Zero-based page") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, at most 500") @RequestParam(defaultValue = "100") int size) {
        AuthenticatedUser user = currentUser.require();
        accessPolicy.requirePlate(user, plate);
        String normalized = Plates.normalize(plate);
        var pageable = PagedResponses.pageable(page, size, NEWEST_FIRST);
        boolean withImagePath = currentUser.hasAuthority(EVIDENCE_PERMISSION);
        List<String> zones = accessPolicy.zoneScopeOf(user).orElse(null);
        return PagedResponses.of((zones != null
                ? violationService.byPlateInZones(normalized, zones, pageable)
                : violationService.byPlate(normalized, pageable))
                .map(v -> ViolationMapper.toResponse(v, withImagePath)));
    }

    @PatchMapping("/{id}/resolve")
    @PreAuthorize("hasAuthority('violation:resolve')")
    @Operation(summary = "Resolve a violation manually",
            description = "Idempotent for an already resolved violation. Publishes violation.resolved with reason MANUAL.")
    @ApiResponse(responseCode = "204", description = "Resolved")
    @ApiResponse(responseCode = "403", description = "Not an operator of the violation's zone")
    @ApiResponse(responseCode = "404", description = "Unknown violation")
    @ApiResponse(responseCode = "409", description = "A fine has already been issued")
    public ResponseEntity<Void> resolve(@PathVariable UUID id) {
        accessPolicy.requireZone(currentUser.require(), violationService.get(id).getZoneId());
        violationService.resolveManually(id);
        return ResponseEntity.noContent().build();
    }
}
