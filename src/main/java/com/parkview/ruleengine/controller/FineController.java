package com.parkview.ruleengine.controller;

import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.dto.response.FineResponse;
import com.parkview.ruleengine.mapper.FineMapper;
import com.parkview.ruleengine.security.AccessPolicy;
import com.parkview.ruleengine.security.AuthenticatedUser;
import com.parkview.ruleengine.security.CurrentUser;
import com.parkview.ruleengine.service.FineService;
import com.parkview.ruleengine.service.Plates;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Read API for fines (the frontend used to read the table directly). Paying goes through payment-service. */
@RestController
@RequestMapping("/api/v1/fines")
@RequiredArgsConstructor
@Tag(name = "Fines", description = "Fines issued by the rule engine (read only)")
public class FineController {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "issuedAt").and(Sort.by("id"));

    private final FineService fineService;
    private final CurrentUser currentUser;
    private final AccessPolicy accessPolicy;

    @GetMapping("/plate/{plate}")
    @PreAuthorize("hasRole('DRIVER') or hasAuthority('violation:read')")
    @Operation(summary = "All fines of a plate",
            description = "Newest first. Drivers may only query plates in their plates claim; operators and administrators holding "
                    + "violation:read any plate.")
    @ApiResponse(responseCode = "200", description = "A page of fines; the total is in the X-Total-Count header")
    @ApiResponse(responseCode = "403", description = "A driver asked for a plate that is not theirs")
    public ResponseEntity<List<FineResponse>> getByPlate(
            @PathVariable String plate,
            @Parameter(description = "Zero-based page") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, at most 500") @RequestParam(defaultValue = "100") int size) {
        accessPolicy.requirePlate(currentUser.require(), plate);
        return PagedResponses.of(fineService
                .byPlate(Plates.normalize(plate), PagedResponses.pageable(page, size, NEWEST_FIRST))
                .map(FineMapper::toResponse));
    }

    @GetMapping("/zone/{zoneId}")
    @PreAuthorize("hasAuthority('violation:read')")
    @Operation(summary = "Fines of a zone",
            description = "Newest first. Needs permission violation:read and, for operators, the zone in their zones claim.")
    @ApiResponse(responseCode = "200", description = "A page of fines; the total is in the X-Total-Count header")
    @ApiResponse(responseCode = "403", description = "Not an operator of this zone")
    public ResponseEntity<List<FineResponse>> getByZone(
            @PathVariable String zoneId,
            @Parameter(description = "Only paid (true) or only unpaid (false) fines; omitted: all")
            @RequestParam(required = false) Boolean paid,
            @Parameter(description = "Zero-based page") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, at most 500") @RequestParam(defaultValue = "100") int size) {
        accessPolicy.requireZone(currentUser.require(), zoneId);
        return PagedResponses.of(fineService
                .byZone(zoneId, paid, PagedResponses.pageable(page, size, NEWEST_FIRST))
                .map(FineMapper::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('DRIVER') or hasAuthority('violation:read')")
    @Operation(summary = "One fine",
            description = "Drivers see fines of their own plates; operators and administrators holding violation:read those of their zones.")
    @ApiResponse(responseCode = "200", description = "The fine")
    @ApiResponse(responseCode = "403", description = "Neither the owner's plate nor a zone of the caller")
    @ApiResponse(responseCode = "404", description = "Unknown fine")
    public FineResponse get(@PathVariable UUID id) {
        AuthenticatedUser user = currentUser.require();
        Fine fine = fineService.get(id);
        if (user.isOperatorOrAdmin()) {
            accessPolicy.requireZone(user, fine.getZoneId());
        } else {
            accessPolicy.requirePlate(user, fine.getPlate());
        }
        return FineMapper.toResponse(fine);
    }
}
