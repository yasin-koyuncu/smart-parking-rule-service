package com.parkview.ruleengine.controller;

import com.parkview.ruleengine.domain.type.DisputeStatus;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.dto.response.FineActionResponse;
import com.parkview.ruleengine.dto.response.FineResponse;
import com.parkview.ruleengine.mapper.FineActionMapper;
import com.parkview.ruleengine.mapper.FineMapper;
import com.parkview.ruleengine.security.AccessPolicy;
import com.parkview.ruleengine.security.AuthenticatedUser;
import com.parkview.ruleengine.security.CurrentUser;
import com.parkview.ruleengine.service.fine.FineQueryService;
import com.parkview.ruleengine.util.Plates;
import com.parkview.ruleengine.web.PagedResponses;
import com.parkview.ruleengine.web.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fines: the read API for drivers and operators (fines of a plate, site or zone, one fine, its audit trail). The operator
 * commands void and mark-paid are in {@link FineManagementController}; online payment goes through payment-service.
 */
@RestController
@RequestMapping("/api/v1/fines")
@RequiredArgsConstructor
@Tag(name = "Fines", description = "Fines issued by the rule engine: read API, the operator commands void and mark-paid, evidence and disputes")
public class FineController {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "issuedAt").and(Sort.by("id"));
    /** The permission that makes the camera snapshot key of a fine visible; drivers never have it (ADR 0008). */
    private static final String EVIDENCE_PERMISSION = "violation:read";
    private static final Sort HISTORY_NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "at").and(Sort.by("id"));

    private final FineQueryService fines;
    private final CurrentUser currentUser;
    private final AccessPolicy accessPolicy;

    @GetMapping("/plate/{plate}")
    @PreAuthorize("hasRole('DRIVER') or hasAuthority('violation:read')")
    @Operation(summary = "All fines of a plate",
            description = "Newest first. Drivers may only query plates in their plates claim and never see voided fines; operators and "
                    + "administrators holding violation:read any plate, without voided fines unless includeVoided=true. An operator sees "
                    + "only the fines at their sites, an administrator those of every site. Only callers with violation:read get imagePath.")
    @ApiResponse(responseCode = "200", description = "A page of fines; the total is in the X-Total-Count header")
    @ApiResponse(responseCode = "403", description = "A driver asked for a plate that is not theirs")
    public ResponseEntity<List<FineResponse>> getByPlate(
            @PathVariable String plate,
            @Parameter(description = "Staff only: also list voided fines (ignored for drivers)")
            @RequestParam(defaultValue = "false") boolean includeVoided,
            @Parameter(description = "Zero-based page") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, at most 500") @RequestParam(defaultValue = "100") int size) {
        AuthenticatedUser user = currentUser.require();
        accessPolicy.requirePlate(user, plate);
        String normalized = Plates.normalize(plate);
        boolean withVoided = includeVoided && user.isOperatorOrAdmin();
        var pageable = PagedResponses.pageable(page, size, NEWEST_FIRST);
        List<String> sites = accessPolicy.siteScopeOf(user).orElse(null);
        return PagedResponses.of(respond(sites != null
                ? fines.byPlateInSites(normalized, withVoided, sites, pageable)
                : fines.byPlate(normalized, withVoided, pageable)));
    }

    @GetMapping("/site/{siteId}")
    @PreAuthorize("hasAuthority('violation:read')")
    @Operation(summary = "Fines of a site",
            description = "In the zones of the site and outside them, newest first, without voided fines unless includeVoided=true. Needs "
                    + "permission violation:read and, for operators, the site in their sites claim.")
    @ApiResponse(responseCode = "200", description = "A page of fines; the total is in the X-Total-Count header")
    @ApiResponse(responseCode = "403", description = "Not an operator of this site")
    public ResponseEntity<List<FineResponse>> getBySite(
            @PathVariable String siteId,
            @Parameter(description = "Only paid (true) or only unpaid (false) fines; omitted: all")
            @RequestParam(required = false) Boolean paid,
            @Parameter(description = "Also list voided fines")
            @RequestParam(defaultValue = "false") boolean includeVoided,
            @Parameter(description = "Zero-based page") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, at most 500") @RequestParam(defaultValue = "100") int size) {
        accessPolicy.requireSite(currentUser.require(), siteId);
        return PagedResponses.of(respond(fines.bySite(siteId, paid, includeVoided,
                PagedResponses.pageable(page, size, NEWEST_FIRST))));
    }

    @GetMapping("/zone/{zoneId}")
    @PreAuthorize("hasAuthority('violation:read')")
    @Operation(summary = "Fines of a zone",
            description = "Newest first, without voided fines unless includeVoided=true. Needs permission violation:read and, for operators, "
                    + "the site of the zone in their sites claim.")
    @ApiResponse(responseCode = "200", description = "A page of fines; the total is in the X-Total-Count header")
    @ApiResponse(responseCode = "403", description = "Not an operator of the site of this zone")
    public ResponseEntity<List<FineResponse>> getByZone(
            @PathVariable String zoneId,
            @Parameter(description = "Only paid (true) or only unpaid (false) fines; omitted: all")
            @RequestParam(required = false) Boolean paid,
            @Parameter(description = "Also list voided fines")
            @RequestParam(defaultValue = "false") boolean includeVoided,
            @Parameter(description = "Zero-based page") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, at most 500") @RequestParam(defaultValue = "100") int size) {
        accessPolicy.requireZone(currentUser.require(), zoneId);
        return PagedResponses.of(respond(fines.byZone(zoneId, paid, includeVoided,
                PagedResponses.pageable(page, size, NEWEST_FIRST))));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('DRIVER') or hasAuthority('violation:read')")
    @Operation(summary = "One fine",
            description = "Drivers see fines of their own plates, but never a voided one (404); operators and administrators holding "
                    + "violation:read those of their sites, voided or not.")
    @ApiResponse(responseCode = "200", description = "The fine")
    @ApiResponse(responseCode = "403", description = "Neither the owner's plate nor a site of the caller")
    @ApiResponse(responseCode = "404", description = "Unknown fine, or a voided fine requested by a driver")
    public FineResponse get(@PathVariable UUID id) {
        AuthenticatedUser user = currentUser.require();
        Fine fine = fines.get(id);
        if (user.isOperatorOrAdmin()) {
            accessPolicy.requireSite(user, fine.getSiteId());
        } else {
            if (fine.isVoided()) {
                throw new ResourceNotFoundException("Fine", id);
            }
            accessPolicy.requirePlate(user, fine.getPlate());
        }
        return FineMapper.toResponse(fine, fines.disputeStatusOf(id), currentUser.hasAuthority(EVIDENCE_PERMISSION));
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAuthority('violation:read')")
    @Operation(summary = "Audit trail of a fine",
            description = "Voids, manual payments, online payments and the dispute lifecycle, newest first. Needs permission violation:read and the site of the fine "
                    + "(administrators: any site).")
    @ApiResponse(responseCode = "200", description = "A page of entries; the total is in the X-Total-Count header")
    @ApiResponse(responseCode = "403", description = "Not an operator of the fine's site")
    @ApiResponse(responseCode = "404", description = "Unknown fine")
    public ResponseEntity<List<FineActionResponse>> history(
            @PathVariable UUID id,
            @Parameter(description = "Zero-based page") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, at most 500") @RequestParam(defaultValue = "100") int size) {
        accessPolicy.requireSite(currentUser.require(), fines.get(id).getSiteId());
        return PagedResponses.of(fines
                .history(id, PagedResponses.pageable(page, size, HISTORY_NEWEST_FIRST))
                .map(FineActionMapper::toResponse));
    }

    /** One extra query for the dispute state of the whole page. */
    private Page<FineResponse> respond(Page<Fine> page) {
        Map<UUID, DisputeStatus> disputeStatuses = fines.disputeStatusesOf(page.getContent());
        boolean withImagePath = currentUser.hasAuthority(EVIDENCE_PERMISSION);
        return page.map(fine -> FineMapper.toResponse(fine, disputeStatuses.get(fine.getId()), withImagePath));
    }
}
