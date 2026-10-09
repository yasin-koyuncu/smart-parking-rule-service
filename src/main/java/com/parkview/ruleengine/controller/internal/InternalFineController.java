package com.parkview.ruleengine.controller.internal;

import com.parkview.ruleengine.dto.response.InternalFineResponse;
import com.parkview.ruleengine.mapper.FineMapper;
import com.parkview.ruleengine.service.FineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Service-to-service API, authenticated with {@code X-Internal-Api-Key}; never routed by the gateway. */
@RestController
@RequestMapping("/internal/v1/fines")
@RequiredArgsConstructor
@Tag(name = "Internal", description = "Service-to-service endpoints (X-Internal-Api-Key)")
public class InternalFineController {

    private final FineService fineService;

    @GetMapping("/{id}")
    @Operation(summary = "Fine details for payment-service",
            description = "The amount to pay is always taken from here, never from a client request.")
    @ApiResponse(responseCode = "200", description = "The fine")
    @ApiResponse(responseCode = "401", description = "Missing or wrong internal API key")
    @ApiResponse(responseCode = "404", description = "Unknown fine")
    public InternalFineResponse get(@PathVariable UUID id) {
        return FineMapper.toInternalResponse(fineService.get(id));
    }
}
