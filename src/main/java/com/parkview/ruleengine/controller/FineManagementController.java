package com.parkview.ruleengine.controller;

import com.parkview.ruleengine.domain.PaidMethod;
import com.parkview.ruleengine.dto.request.MarkFinePaidRequest;
import com.parkview.ruleengine.dto.request.VoidFineRequest;
import com.parkview.ruleengine.dto.response.FineResponse;
import com.parkview.ruleengine.mapper.FineMapper;
import com.parkview.ruleengine.security.AccessPolicy;
import com.parkview.ruleengine.security.AuthenticatedUser;
import com.parkview.ruleengine.security.CurrentUser;
import com.parkview.ruleengine.service.FineManagementService;
import com.parkview.ruleengine.service.FineQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The operator commands on a fine, void and mark-paid (permission {@code fine:manage}). The read API is
 * {@link FineController}; online payment goes through payment-service.
 */
@RestController
@RequestMapping("/api/v1/fines")
@RequiredArgsConstructor
@Tag(name = "Fines")
public class FineManagementController {

    private final FineQueryService fineQuery;
    private final FineManagementService fineManagement;
    private final CurrentUser currentUser;
    private final AccessPolicy accessPolicy;

    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('fine:manage')")
    @Operation(summary = "Void an unpaid fine",
            description = "Needs permission fine:manage and the zone of the fine (administrators: any zone). Idempotent: an already voided "
                    + "fine is returned unchanged. A voided fine can no longer be paid. Publishes fine.voided on the first call.")
    @ApiResponse(responseCode = "200", description = "The voided fine")
    @ApiResponse(responseCode = "400", description = "Invalid body (reason is required, 3 to 300 characters)")
    @ApiResponse(responseCode = "403", description = "Missing permission, or not an operator of the fine's zone")
    @ApiResponse(responseCode = "404", description = "Unknown fine")
    @ApiResponse(responseCode = "409", description = "The fine is already paid; a refund is a separate flow")
    public FineResponse voidFine(@PathVariable UUID id, @Valid @RequestBody VoidFineRequest request) {
        AuthenticatedUser user = currentUser.require();
        accessPolicy.requireZone(user, fineQuery.get(id).getZoneId());
        return FineMapper.toResponse(fineManagement.voidFine(id, request.reason(), actorOf(user)));
    }

    @PostMapping("/{id}/mark-paid")
    @PreAuthorize("hasAuthority('fine:manage')")
    @Operation(summary = "Mark an unpaid fine as paid",
            description = "For payments outside the online flow (cash, transfer, other). Needs permission fine:manage and the zone of the "
                    + "fine (administrators: any zone). Idempotent: an already paid fine is returned unchanged. Publishes fine.paid on the first call.")
    @ApiResponse(responseCode = "200", description = "The paid fine")
    @ApiResponse(responseCode = "400", description = "Invalid body (reason 3 to 300 characters, method cash, transfer or other)")
    @ApiResponse(responseCode = "403", description = "Missing permission, or not an operator of the fine's zone")
    @ApiResponse(responseCode = "404", description = "Unknown fine")
    @ApiResponse(responseCode = "409", description = "The fine is voided")
    public FineResponse markPaid(@PathVariable UUID id, @Valid @RequestBody MarkFinePaidRequest request) {
        AuthenticatedUser user = currentUser.require();
        accessPolicy.requireZone(user, fineQuery.get(id).getZoneId());
        PaidMethod method = PaidMethod.parse(request.method())
                .orElseThrow(() -> new IllegalStateException("method is validated by the request"));
        return FineMapper.toResponse(fineManagement.markPaidManually(id, request.reason(), method, actorOf(user)));
    }

    /** The audit trail records who acted: the token subject, which Supabase always issues as a UUID. */
    private static UUID actorOf(AuthenticatedUser user) {
        try {
            return UUID.fromString(user.userId());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new AccessDeniedException("Token subject is not a user id");
        }
    }
}
