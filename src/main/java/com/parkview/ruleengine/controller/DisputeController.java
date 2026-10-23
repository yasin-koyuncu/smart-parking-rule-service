package com.parkview.ruleengine.controller;

import com.parkview.ruleengine.domain.Dispute;
import com.parkview.ruleengine.domain.type.DisputeChannel;
import com.parkview.ruleengine.domain.type.DisputeStatus;
import com.parkview.ruleengine.dto.request.DisputeDecisionRequest;
import com.parkview.ruleengine.dto.request.DisputeRequest;
import com.parkview.ruleengine.dto.response.DisputeResponse;
import com.parkview.ruleengine.mapper.DisputeMapper;
import com.parkview.ruleengine.security.AccessPolicy;
import com.parkview.ruleengine.security.AuthenticatedUser;
import com.parkview.ruleengine.security.CurrentUser;
import com.parkview.ruleengine.service.fine.DisputeQueryService;
import com.parkview.ruleengine.service.fine.DisputeService;
import com.parkview.ruleengine.util.ZoneIds;
import com.parkview.ruleengine.web.PagedResponses;
import com.parkview.ruleengine.web.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Disputes: a driver (or an operator on the phone with a driver) asks for a person to review a fine; while the dispute is
 * open the fine cannot be paid; an operator with {@code fine:manage} upholds or voids the fine with a written reason.
 * A fine or dispute the caller may not see is answered with 404, never 403, so its existence is not revealed.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Disputes", description = "Reviews of fines by a person: open, withdraw, list and decide")
public class DisputeController {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "openedAt").and(Sort.by("id"));

    private final FineAccess access;
    private final DisputeService disputeService;
    private final DisputeQueryService disputes;
    private final CurrentUser currentUser;
    private final AccessPolicy accessPolicy;

    @PostMapping("/fines/{id}/dispute")
    @PreAuthorize("hasRole('DRIVER') or hasAuthority('fine:manage')")
    @Operation(summary = "Ask for a review of a fine",
            description = "The driver who owns the plate (channel driver), or an operator with fine:manage in the zone of the fine "
                    + "(channel operator, the customer called). While the dispute is open the fine cannot be paid. The message is "
                    + "personal text and is never logged.")
    @ApiResponse(responseCode = "201", description = "The new dispute")
    @ApiResponse(responseCode = "400", description = "The message is missing or not 10 to 1000 characters")
    @ApiResponse(responseCode = "403", description = "An operator without fine:manage")
    @ApiResponse(responseCode = "404", description = "Unknown fine, another driver fine, or a fine outside the caller zones")
    @ApiResponse(responseCode = "409", description = "The fine is paid or cancelled, a dispute is already open, or the fine was already upheld")
    public ResponseEntity<DisputeResponse> open(@PathVariable UUID id, @Valid @RequestBody DisputeRequest request) {
        AuthenticatedUser user = currentUser.require();
        access.forOwnerOrStaff(id, user);
        DisputeChannel channel = user.isOperatorOrAdmin() ? DisputeChannel.OPERATOR : DisputeChannel.DRIVER;
        UUID by = user.userUuid();
        if (channel == DisputeChannel.OPERATOR && by == null) {
            throw notAUser();
        }
        Dispute dispute = disputeService.open(id, channel, by, request.message());
        return ResponseEntity.created(URI.create("/api/v1/fines/" + id + "/dispute"))
                .body(DisputeMapper.toResponse(disputes.detailsOf(dispute)));
    }

    @GetMapping("/fines/{id}/dispute")
    @PreAuthorize("hasRole('DRIVER') or hasAuthority('violation:read') or hasAuthority('fine:manage')")
    @Operation(summary = "The latest dispute of a fine",
            description = "For the driver who owns the plate and for operators of the zone; also for a voided fine (the outcome of the dispute).")
    @ApiResponse(responseCode = "200", description = "The latest dispute")
    @ApiResponse(responseCode = "404", description = "Unknown fine, another driver fine, a fine outside the caller zones, or no dispute yet")
    public DisputeResponse latest(@PathVariable UUID id) {
        access.forOwnerOrStaff(id, currentUser.require());
        Dispute dispute = disputes.latestOf(id).orElseThrow(() -> new ResourceNotFoundException("Dispute of fine", id));
        return DisputeMapper.toResponse(disputes.detailsOf(dispute));
    }

    @PostMapping("/disputes/{id}/withdraw")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "Withdraw an open dispute",
            description = "Only the driver who owns the plate, while the dispute is open. The payment hold ends.")
    @ApiResponse(responseCode = "200", description = "The withdrawn dispute")
    @ApiResponse(responseCode = "403", description = "Not a driver")
    @ApiResponse(responseCode = "404", description = "Unknown dispute or another driver dispute")
    @ApiResponse(responseCode = "409", description = "The dispute is not open any more")
    public DisputeResponse withdraw(@PathVariable UUID id) {
        AuthenticatedUser user = currentUser.require();
        Dispute dispute = disputes.get(id);
        if (!accessPolicy.canAccessPlate(user, dispute.getPlate())) {
            throw new ResourceNotFoundException("Dispute", id);
        }
        return DisputeMapper.toResponse(disputes.detailsOf(disputeService.withdraw(id, user.userUuid())));
    }

    @GetMapping("/disputes")
    @PreAuthorize("hasAuthority('fine:manage')")
    @Operation(summary = "Disputes to review",
            description = "Newest first, limited to the zones in the caller token (administrators: all). status open (default), "
                    + "decided (upheld, voided or withdrawn) or all. Needs permission fine:manage.")
    @ApiResponse(responseCode = "200", description = "A page of disputes; the total is in the X-Total-Count header")
    @ApiResponse(responseCode = "400", description = "Unknown status")
    @ApiResponse(responseCode = "403", description = "Missing permission, or zoneId outside the caller zones")
    public ResponseEntity<List<DisputeResponse>> list(
            @Parameter(description = "open (default), decided or all")
            @RequestParam(defaultValue = "open") @Pattern(regexp = "open|decided|all", message = "must be open, decided or all") String status,
            @Parameter(description = "Only this zone (must be one of the caller zones)") @RequestParam(required = false) String zoneId,
            @Parameter(description = "Zero-based page") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, at most 500") @RequestParam(defaultValue = "100") int size) {
        AuthenticatedUser user = currentUser.require();
        String zone = ZoneIds.normalize(zoneId);
        Collection<String> zones;
        if (zone != null) {
            accessPolicy.requireZone(user, zone);
            zones = List.of(zone);
        } else {
            zones = user.isAdmin() ? null : user.zones();
        }
        return PagedResponses.of(disputes
                .list(DisputeQueryService.Filter.valueOf(status.toUpperCase(Locale.ROOT)), zones,
                        PagedResponses.pageable(page, size, NEWEST_FIRST))
                .map(DisputeMapper::toResponse));
    }

    @PostMapping("/disputes/{id}/decision")
    @PreAuthorize("hasAuthority('fine:manage')")
    @Operation(summary = "Decide a dispute",
            description = "An operator with fine:manage in the zone of the fine reviews the evidence and either upholds the fine (the payment "
                    + "hold ends) or voids it (same as the void command; the note becomes the void reason). The written note is mandatory "
                    + "and kept in the audit trail.")
    @ApiResponse(responseCode = "200", description = "The decided dispute")
    @ApiResponse(responseCode = "400", description = "Invalid outcome, or a note that is missing or not 10 to 1000 characters")
    @ApiResponse(responseCode = "403", description = "Missing permission fine:manage")
    @ApiResponse(responseCode = "404", description = "Unknown dispute, or a dispute outside the caller zones")
    @ApiResponse(responseCode = "409", description = "The dispute was decided or withdrawn already")
    public DisputeResponse decide(@PathVariable UUID id, @Valid @RequestBody DisputeDecisionRequest request) {
        AuthenticatedUser user = currentUser.require();
        Dispute dispute = disputes.get(id);
        if (!accessPolicy.canAccessZone(user, dispute.getZoneId())) {
            throw new ResourceNotFoundException("Dispute", id);
        }
        UUID actor = user.userUuid();
        if (actor == null) {
            throw notAUser();
        }
        DisputeStatus outcome = DisputeStatus.parse(request.outcome()).orElseThrow();
        return DisputeMapper.toResponse(disputes.detailsOf(disputeService.decide(id, outcome, actor, request.note())));
    }

    /** The audit trail records who acted: the token subject, which Supabase always issues as a UUID. */
    private static AccessDeniedException notAUser() {
        return new AccessDeniedException("Token subject is not a user id");
    }
}
