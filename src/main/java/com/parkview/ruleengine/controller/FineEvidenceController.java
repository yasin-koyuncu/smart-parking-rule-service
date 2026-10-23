package com.parkview.ruleengine.controller;

import com.parkview.ruleengine.dto.response.EvidenceResponse;
import com.parkview.ruleengine.dto.response.ExplanationResponse;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.mapper.EvidenceMapper;
import com.parkview.ruleengine.security.CurrentUser;
import com.parkview.ruleengine.service.fine.FineEvidenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * What a fine was based on. Operators get the frozen evidence including the camera snapshots; the driver gets a reduced,
 * plain-language explanation without snapshots and detection ids (they can show other people's cars).
 */
@RestController
@RequestMapping("/api/v1/fines")
@RequiredArgsConstructor
@Tag(name = "Fines")
public class FineEvidenceController {

    private final FineAccess access;
    private final FineEvidenceService evidence;
    private final CurrentUser currentUser;

    @GetMapping("/{id}/evidence")
    @PreAuthorize("hasAuthority('violation:read')")
    @Operation(summary = "Frozen evidence of a fine",
            description = "What the fine was based on when it was issued, including the camera snapshots (imagePath keys). Needs permission "
                    + "violation:read and the zone of the fine (administrators: any zone). Fines issued before evidence was recorded "
                    + "answer {recorded:false}.")
    @ApiResponse(responseCode = "200", description = "The evidence, or {recorded:false}")
    @ApiResponse(responseCode = "403", description = "Missing permission violation:read")
    @ApiResponse(responseCode = "404", description = "Unknown fine, or a fine outside the caller's zones")
    public EvidenceResponse evidence(@PathVariable UUID id) {
        access.forStaff(id, currentUser.require());
        return EvidenceMapper.toResponse(id, evidence.find(id).orElse(null));
    }

    @GetMapping("/{id}/explanation")
    @PreAuthorize("hasRole('DRIVER') or hasAuthority('violation:read')")
    @Operation(summary = "Plain-language explanation of a fine",
            description = "Why the fine was issued, for the driver who owns the plate and for operators of the zone. Never contains snapshots "
                    + "or detection ids. Works for voided fines too (the driver can see the outcome of a dispute).")
    @ApiResponse(responseCode = "200", description = "The explanation")
    @ApiResponse(responseCode = "404", description = "Unknown fine, another driver's fine, or a fine outside the caller's zones")
    public ExplanationResponse explanation(@PathVariable UUID id) {
        Fine fine = access.forOwnerOrStaff(id, currentUser.require());
        return EvidenceMapper.toExplanation(evidence.explain(fine));
    }
}
