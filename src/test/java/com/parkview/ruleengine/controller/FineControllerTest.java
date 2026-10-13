package com.parkview.ruleengine.controller;

import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.ViolationType;
import com.parkview.ruleengine.security.AccessPolicy;
import com.parkview.ruleengine.security.CurrentUser;
import com.parkview.ruleengine.security.JwtSecurityConfig;
import com.parkview.ruleengine.service.FineService;
import com.parkview.ruleengine.web.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.parkview.ruleengine.controller.WebTestSupport.token;
import static com.parkview.ruleengine.controller.WebTestSupport.user;
import static com.parkview.ruleengine.controller.WebTestSupport.userWith;
import static com.parkview.ruleengine.controller.WebTestSupport.userWithoutFallback;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FineController.class)
@Import({JwtSecurityConfig.class, AccessPolicy.class, CurrentUser.class})
@TestPropertySource(properties = {
        "parkview.security.issuer=http://issuer.test/auth/v1",
        "parkview.security.internal-api-key=" + WebTestSupport.INTERNAL_KEY})
class FineControllerTest {

    private static final Instant T = Instant.parse("2026-10-09T10:00:00Z");
    private static final UUID OWNER = UUID.fromString("7d0c2a56-6b53-4d4e-9a0c-0f9d1f4f4e11");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    FineService fineService;

    private Fine fine;

    @BeforeEach
    void setUp() {
        Violation violation = Violation.open(UUID.randomUUID(), "z1", "ABC123", OWNER, ViolationType.WRONG_PERMIT, T, T);
        fine = Fine.issue(violation, new BigDecimal("700"), OWNER, T);
        ReflectionTestUtils.setField(fine, "id", UUID.fromString("11111111-2222-3333-4444-555555555555"));
        ReflectionTestUtils.setField(fine, "cameraId", "cam-1");
        ReflectionTestUtils.setField(fine, "reasonCode", "WP");
        ReflectionTestUtils.setField(fine, "reasonText", "Missing permit");
        ReflectionTestUtils.setField(fine, "imagePath", "fines/1.jpg");
    }

    private static PageImpl<Fine> page(Fine... fines) {
        return new PageImpl<>(List.of(fines), PageRequest.of(0, 100), fines.length);
    }

    // ---- JSON shape ----

    @Test
    void fineJsonHasExactlyTheDocumentedFieldsAndNeverTheOwner() throws Exception {
        when(fineService.byPlate(eq("ABC123"), any())).thenReturn(page(fine));

        mvc.perform(get("/api/v1/fines/plate/ABC123").with(user("driver", List.of("ABC123"), List.of())))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath("$[0].id").value("11111111-2222-3333-4444-555555555555"))
                .andExpect(jsonPath("$[0].plate").value("ABC123"))
                .andExpect(jsonPath("$[0].zoneId").value("z1"))
                .andExpect(jsonPath("$[0].cameraId").value("cam-1"))
                .andExpect(jsonPath("$[0].reason").value("wrong_permit"))
                .andExpect(jsonPath("$[0].reasonCode").value("WP"))
                .andExpect(jsonPath("$[0].reasonText").value("Missing permit"))
                .andExpect(jsonPath("$[0].amountSek").value(700.0))
                .andExpect(jsonPath("$[0].issuedAt").value("2026-10-09T10:00:00Z"))
                .andExpect(jsonPath("$[0].paid").value(false))
                .andExpect(jsonPath("$[0].imagePath").value("fines/1.jpg"))
                .andExpect(jsonPath("$[0].userId").doesNotExist())
                .andExpect(jsonPath("$[0].length()").value(11));
    }

    @Test
    void singleFineHasTheSameShape() throws Exception {
        when(fineService.get(fine.getId())).thenReturn(fine);

        mvc.perform(get("/api/v1/fines/{id}", fine.getId()).with(user("admin", List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("wrong_permit"))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.length()").value(11));
    }

    // ---- GET /plate/{plate} ----

    @Test
    void anonymousIsUnauthorizedWithProblemJson() throws Exception {
        for (String url : List.of("/api/v1/fines/plate/ABC123", "/api/v1/fines/zone/z1", "/api/v1/fines/" + UUID.randomUUID())) {
            mvc.perform(get(url))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
    }

    @Test
    void driverReadsOwnPlateWhichIsNormalisedAndNeverAnotherOne() throws Exception {
        when(fineService.byPlate(eq("ABC123"), any())).thenReturn(page(fine));

        mvc.perform(get("/api/v1/fines/plate/abc-123").with(user("driver", List.of("ABC 123"), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].plate").value("ABC123"));
        mvc.perform(get("/api/v1/fines/plate/XYZ999").with(user("driver", List.of("ABC123"), List.of())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(get("/api/v1/fines/plate/XYZ999").with(userWithoutFallback("driver", List.of("ABC123"), List.of())))
                .andExpect(status().isForbidden());
        verify(fineService, never()).byPlate(eq("XYZ999"), any());
    }

    @Test
    void staffWithViolationReadMayReadAnyPlate() throws Exception {
        when(fineService.byPlate(eq("XYZ999"), any())).thenReturn(page());

        for (var allowed : List.of(user("operator", List.of(), List.of("z1")), user("admin", List.of(), List.of()),
                userWith("operator", List.of("violation:read"), List.of(), List.of("z1")),
                token(false, "admin", List.of("violation:read"), List.of(), List.of()))) {
            mvc.perform(get("/api/v1/fines/plate/XYZ999").with(allowed)).andExpect(status().isOk());
        }
    }

    @Test
    void staffWithoutViolationReadAndUnknownRolesAreForbiddenOnThePlateList() throws Exception {
        for (var denied : List.of(userWith("operator", List.of("mapping:view", "violation:resolve"), List.of(), List.of("z1")),
                userWith("operator", List.of(), List.of(), List.of("z1")), userWithoutFallback("operator", List.of(), List.of("z1")),
                userWithoutFallback("admin", List.of(), List.of()), user("hacker", List.of("ABC123"), List.of()))) {
            mvc.perform(get("/api/v1/fines/plate/ABC123").with(denied)).andExpect(status().isForbidden());
        }
        verify(fineService, never()).byPlate(any(), any());
    }

    @Test
    void plateListIsNewestFirstAndBounded() throws Exception {
        when(fineService.byPlate(eq("ABC123"), any())).thenReturn(page());

        mvc.perform(get("/api/v1/fines/plate/ABC123?page=-3&size=100000").with(user("admin", List.of(), List.of())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/fines/plate/ABC123?size=0").with(user("admin", List.of(), List.of())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/fines/plate/ABC123").with(user("admin", List.of(), List.of())))
                .andExpect(status().isOk());

        verify(fineService).byPlate(eq("ABC123"), argThat((Pageable p) -> p.getPageSize() == 500 && p.getPageNumber() == 0
                && p.getSort().getOrderFor("issuedAt").isDescending()));
        verify(fineService).byPlate(eq("ABC123"), argThat((Pageable p) -> p.getPageSize() == 1));
        verify(fineService).byPlate(eq("ABC123"), argThat((Pageable p) -> p.getPageSize() == 100));
    }

    // ---- GET /zone/{zoneId} ----

    @Test
    void zoneListNeedsViolationReadAndTheZone() throws Exception {
        when(fineService.byZone(eq("z1"), isNull(), any())).thenReturn(page(fine));

        for (var allowed : List.of(user("operator", List.of(), List.of("z1")), user("admin", List.of(), List.of()),
                userWith("operator", List.of("violation:read"), List.of(), List.of("z1")),
                token(false, "admin", List.of("violation:read"), List.of(), List.of()))) {
            mvc.perform(get("/api/v1/fines/zone/z1").with(allowed))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Total-Count", "1"));
        }
        for (var denied : List.of(userWith("operator", List.of("violation:resolve", "permit:manage"), List.of(), List.of("z1")),
                userWith("operator", List.of(), List.of(), List.of("z1")), userWithoutFallback("operator", List.of(), List.of("z1")),
                userWithoutFallback("admin", List.of(), List.of()),
                user("driver", List.of("ABC123"), List.of("z1")))) {
            mvc.perform(get("/api/v1/fines/zone/z1").with(denied))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
    }

    @Test
    void operatorCannotReadAnotherZoneEvenWithThePermission() throws Exception {
        mvc.perform(get("/api/v1/fines/zone/z2").with(userWith("operator", List.of("violation:read"), List.of(), List.of("z1"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/fines/zone/z2").with(user("operator", List.of(), List.of("z1"))))
                .andExpect(status().isForbidden());
        verify(fineService, never()).byZone(any(), any(), any());
    }

    @Test
    void adminMayReadAnyZone() throws Exception {
        when(fineService.byZone(eq("zX"), isNull(), any())).thenReturn(page());

        mvc.perform(get("/api/v1/fines/zone/zX").with(user("admin", List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void zoneListPassesThePaidFilterAndIsBounded() throws Exception {
        when(fineService.byZone(eq("z1"), any(), any())).thenReturn(page());
        var operator = user("operator", List.of(), List.of("z1"));

        mvc.perform(get("/api/v1/fines/zone/z1?paid=true").with(operator)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/fines/zone/z1?paid=false").with(operator)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/fines/zone/z1?page=-1&size=99999").with(operator)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/fines/zone/z1?paid=maybe").with(operator)).andExpect(status().isBadRequest());

        verify(fineService).byZone(eq("z1"), eq(true), any());
        verify(fineService).byZone(eq("z1"), eq(false), any());
        verify(fineService).byZone(eq("z1"), isNull(), argThat((Pageable p) -> p.getPageSize() == 500 && p.getPageNumber() == 0
                && p.getSort().getOrderFor("issuedAt").isDescending()));
    }

    // ---- GET /{id} ----

    @Test
    void driverReadsOwnFineButNotSomeoneElsesAndOperatorsNeedTheZone() throws Exception {
        when(fineService.get(fine.getId())).thenReturn(fine);

        mvc.perform(get("/api/v1/fines/{id}", fine.getId()).with(user("driver", List.of("ABC123"), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fine.getId().toString()));
        mvc.perform(get("/api/v1/fines/{id}", fine.getId()).with(user("driver", List.of("XYZ999"), List.of())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/fines/{id}", fine.getId()).with(user("operator", List.of(), List.of("z1"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/fines/{id}", fine.getId()).with(user("admin", List.of(), List.of())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/fines/{id}", fine.getId()).with(user("operator", List.of(), List.of("z2"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/fines/{id}", fine.getId())
                        .with(userWith("operator", List.of("violation:resolve"), List.of(), List.of("z1"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/fines/{id}", fine.getId()).with(userWithoutFallback("operator", List.of(), List.of("z1"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownFineIsNotFoundWithProblemJson() throws Exception {
        UUID id = UUID.randomUUID();
        when(fineService.get(id)).thenThrow(new ResourceNotFoundException("Fine", id));

        mvc.perform(get("/api/v1/fines/{id}", id).with(user("admin", List.of(), List.of())))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void malformedIdIsABadRequestNotAServerError() throws Exception {
        mvc.perform(get("/api/v1/fines/not-a-uuid").with(user("admin", List.of(), List.of())))
                .andExpect(status().isBadRequest());
    }
}
