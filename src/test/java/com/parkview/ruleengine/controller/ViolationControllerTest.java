package com.parkview.ruleengine.controller;

import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.ViolationType;
import com.parkview.ruleengine.security.AccessPolicy;
import com.parkview.ruleengine.security.CurrentUser;
import com.parkview.ruleengine.security.JwtSecurityConfig;
import com.parkview.ruleengine.service.ViolationService;
import com.parkview.ruleengine.web.ConflictException;
import com.parkview.ruleengine.web.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.parkview.ruleengine.controller.WebTestSupport.user;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ViolationController.class)
@Import({JwtSecurityConfig.class, AccessPolicy.class, CurrentUser.class})
@TestPropertySource(properties = {
        "parkview.security.issuer=http://issuer.test/auth/v1",
        "parkview.security.internal-api-key=" + WebTestSupport.INTERNAL_KEY})
class ViolationControllerTest {

    private static final Instant T = Instant.parse("2026-10-09T10:00:00Z");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ViolationService violationService;

    private Violation violation;

    @BeforeEach
    void setUp() {
        violation = Violation.open(UUID.randomUUID(), "z1", "ABC123", null, ViolationType.WRONG_PERMIT, T, T.plusSeconds(600));
        ReflectionTestUtils.setField(violation, "id", UUID.randomUUID());
    }

    @Test
    void anonymousIsUnauthorizedWithProblemJson() throws Exception {
        mvc.perform(get("/api/v1/violations/zone/z1"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void operatorReadsActiveViolationsOfOwnZoneWithTheSameJsonShapeAsBefore() throws Exception {
        when(violationService.activeByZone(eq("z1"), any()))
                .thenReturn(new PageImpl<>(List.of(violation), PageRequest.of(0, 100), 1));

        mvc.perform(get("/api/v1/violations/zone/z1").with(user("operator", List.of(), List.of("z1"))))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath("$[0].id").value(violation.getId().toString()))
                .andExpect(jsonPath("$[0].spotId").value(violation.getSpotId().toString()))
                .andExpect(jsonPath("$[0].zoneId").value("z1"))
                .andExpect(jsonPath("$[0].plate").value("ABC123"))
                .andExpect(jsonPath("$[0].violationType").value("WRONG_PERMIT"))
                .andExpect(jsonPath("$[0].detectedAt").value("2026-10-09T10:00:00Z"))
                .andExpect(jsonPath("$[0].graceUntil").value("2026-10-09T10:10:00Z"))
                .andExpect(jsonPath("$[0].resolvedAt").doesNotExist())
                .andExpect(jsonPath("$[0].fineId").doesNotExist());
    }

    @Test
    void zoneListIsForbiddenForOtherZonesAndForDrivers() throws Exception {
        mvc.perform(get("/api/v1/violations/zone/z2").with(user("operator", List.of(), List.of("z1"))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(get("/api/v1/violations/zone/z1").with(user("driver", List.of("ABC123"), List.of("z1"))))
                .andExpect(status().isForbidden());
        verify(violationService, never()).activeByZone(any(), any());
    }

    @Test
    void adminMayReadAnyZone() throws Exception {
        when(violationService.activeByZone(eq("zX"), any())).thenReturn(new PageImpl<>(List.of()));

        mvc.perform(get("/api/v1/violations/zone/zX").with(user("admin", List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void pageSizeIsBounded() throws Exception {
        when(violationService.activeByZone(eq("z1"), any())).thenReturn(new PageImpl<>(List.of()));

        mvc.perform(get("/api/v1/violations/zone/z1?page=-3&size=100000").with(user("operator", List.of(), List.of("z1"))))
                .andExpect(status().isOk());

        verify(violationService).activeByZone(eq("z1"), org.mockito.ArgumentMatchers.argThat(
                (org.springframework.data.domain.Pageable p) -> p.getPageSize() == 500 && p.getPageNumber() == 0));
    }

    @Test
    void driverMayReadOnlyOwnPlateAndPlateIsNormalised() throws Exception {
        when(violationService.byPlate(eq("ABC123"), any())).thenReturn(new PageImpl<>(List.of(violation)));

        mvc.perform(get("/api/v1/violations/plate/abc-123").with(user("driver", List.of("ABC 123"), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].plate").value("ABC123"));
        mvc.perform(get("/api/v1/violations/plate/XYZ999").with(user("driver", List.of("ABC123"), List.of())))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorsAndAdminsMayReadAnyPlate() throws Exception {
        when(violationService.byPlate(eq("XYZ999"), any())).thenReturn(new PageImpl<>(List.of()));

        mvc.perform(get("/api/v1/violations/plate/XYZ999").with(user("operator", List.of(), List.of("z1"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/violations/plate/XYZ999").with(user("admin", List.of(), List.of())))
                .andExpect(status().isOk());
    }

    @Test
    void tokenWithoutAKnownRoleIsRejected() throws Exception {
        mvc.perform(get("/api/v1/violations/plate/ABC123").with(user("hacker", List.of("ABC123"), List.of())))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorOfTheZoneCanResolve() throws Exception {
        when(violationService.get(violation.getId())).thenReturn(violation);

        mvc.perform(patch("/api/v1/violations/{id}/resolve", violation.getId()).with(user("operator", List.of(), List.of("z1"))))
                .andExpect(status().isNoContent());

        verify(violationService).resolveManually(violation.getId());
    }

    @Test
    void resolveIsForbiddenForOtherZonesAndDrivers() throws Exception {
        when(violationService.get(violation.getId())).thenReturn(violation);

        mvc.perform(patch("/api/v1/violations/{id}/resolve", violation.getId()).with(user("operator", List.of(), List.of("z2"))))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/violations/{id}/resolve", violation.getId()).with(user("driver", List.of("ABC123"), List.of())))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/violations/{id}/resolve", violation.getId()))
                .andExpect(status().isUnauthorized());

        verify(violationService, never()).resolveManually(any());
    }

    @Test
    void resolveOfAnUnknownViolationIsNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(violationService.get(id)).thenThrow(new ResourceNotFoundException("Violation", id));

        mvc.perform(patch("/api/v1/violations/{id}/resolve", id).with(user("admin", List.of(), List.of())))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void resolveOfAFinedViolationIsAConflict() throws Exception {
        when(violationService.get(violation.getId())).thenReturn(violation);
        doThrow(new ConflictException("A fine has already been issued for this violation"))
                .when(violationService).resolveManually(violation.getId());

        mvc.perform(patch("/api/v1/violations/{id}/resolve", violation.getId()).with(user("operator", List.of(), List.of("z1"))))
                .andExpect(status().isConflict());
    }

    @Test
    void malformedIdIsABadRequestNotAServerError() throws Exception {
        mvc.perform(patch("/api/v1/violations/not-a-uuid/resolve").with(user("admin", List.of(), List.of())))
                .andExpect(status().isBadRequest());
    }
}
