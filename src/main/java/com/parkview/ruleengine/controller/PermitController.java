package com.parkview.ruleengine.controller;

import com.parkview.ruleengine.domain.PlatePermit;
import com.parkview.ruleengine.dto.request.CreatePermitRequest;
import com.parkview.ruleengine.dto.response.PermitResponse;
import com.parkview.ruleengine.mapper.PermitMapper;
import com.parkview.ruleengine.security.AccessPolicy;
import com.parkview.ruleengine.security.AuthenticatedUser;
import com.parkview.ruleengine.security.CurrentUser;
import com.parkview.ruleengine.service.violation.PermitService;
import com.parkview.ruleengine.util.ZoneIds;
import com.parkview.ruleengine.web.PagedResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/permits")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('permit:manage')")
@Tag(name = "Permits", description = "Permits that allow a plate to park in permit spots. Permission permit:manage.")
public class PermitController {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"));

    private final PermitService permitService;
    private final CurrentUser currentUser;
    private final AccessPolicy accessPolicy;

    @PostMapping
    @Operation(summary = "Grant a permit",
            description = "Valid in a zone (zoneId; its site is taken), in the whole of a site (siteId without zoneId) or, with neither, at "
                    + "every site. Operators may grant permits for the sites in their sites claim; a permit for every site needs an administrator.")
    @ApiResponse(responseCode = "201", description = "Created; Location points to the new permit")
    @ApiResponse(responseCode = "400", description = "Invalid body")
    @ApiResponse(responseCode = "403", description = "Not allowed for this site")
    @ApiResponse(responseCode = "422", description = "validTo is not after validFrom, or the zone belongs to another site than siteId")
    public ResponseEntity<PermitResponse> create(@Valid @RequestBody CreatePermitRequest request) {
        AuthenticatedUser user = currentUser.require();
        accessPolicy.requireSite(user, permitService.siteOf(request));
        PlatePermit permit = permitService.grant(request, user.userId());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").build(permit.getId());
        return ResponseEntity.created(location).body(PermitMapper.toResponse(permit));
    }

    @GetMapping
    @Operation(summary = "List permits",
            description = "Newest first. Operators see the permits of their sites and the permits valid at every site; administrators see all.")
    @ApiResponse(responseCode = "200", description = "A page of permits; the total is in the X-Total-Count header")
    public ResponseEntity<List<PermitResponse>> list(
            @Parameter(description = "Only this plate") @RequestParam(required = false) String plate,
            @Parameter(description = "Only this site") @RequestParam(required = false) String siteId,
            @Parameter(description = "Only this zone") @RequestParam(required = false) String zoneId,
            @Parameter(description = "Zero-based page") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, at most 500") @RequestParam(defaultValue = "100") int size) {
        AuthenticatedUser user = currentUser.require();
        String site = ZoneIds.normalize(siteId);
        String zone = ZoneIds.normalize(zoneId);
        if (site != null) {
            accessPolicy.requireSite(user, site);
        }
        if (zone != null) {
            accessPolicy.requireZone(user, zone);
        }
        List<String> visibleSites = user.isAdmin() ? null : user.sites();
        return PagedResponses.of(permitService
                .search(plate, site, zone, visibleSites, PagedResponses.pageable(page, size, NEWEST_FIRST))
                .map(PermitMapper::toResponse));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a permit")
    @ApiResponse(responseCode = "200", description = "The permit")
    @ApiResponse(responseCode = "403", description = "The permit belongs to a site the operator does not manage")
    @ApiResponse(responseCode = "404", description = "Unknown permit")
    public PermitResponse get(@PathVariable UUID id) {
        PlatePermit permit = permitService.get(id);
        if (permit.getSiteId() != null) {
            accessPolicy.requireSite(currentUser.require(), permit.getSiteId());
        }
        return PermitMapper.toResponse(permit);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Revoke a permit", description = "Permits valid at every site can only be revoked by an administrator.")
    @ApiResponse(responseCode = "204", description = "Revoked")
    @ApiResponse(responseCode = "403", description = "Not allowed for this site")
    @ApiResponse(responseCode = "404", description = "Unknown permit")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        accessPolicy.requireSite(currentUser.require(), permitService.get(id).getSiteId());
        permitService.revoke(id);
        return ResponseEntity.noContent().build();
    }
}
