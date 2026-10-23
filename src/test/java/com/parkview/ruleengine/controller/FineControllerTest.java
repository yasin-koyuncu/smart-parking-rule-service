package com.parkview.ruleengine.controller;

import com.parkview.ruleengine.domain.type.DisputeStatus;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.type.PaidMethod;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.type.ViolationType;
import com.parkview.ruleengine.security.AccessPolicy;
import com.parkview.ruleengine.security.CurrentUser;
import com.parkview.ruleengine.security.JwtSecurityConfig;
import com.parkview.ruleengine.service.fine.FineQueryService;
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
import java.util.Map;
import java.util.UUID;

import static com.parkview.ruleengine.controller.WebTestSupport.token;
import static com.parkview.ruleengine.controller.WebTestSupport.user;
import static com.parkview.ruleengine.controller.WebTestSupport.userWith;
import static com.parkview.ruleengine.controller.WebTestSupport.userWithoutFallback;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    FineQueryService fineService;

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
        when(fineService.byPlate(eq("ABC123"), eq(false), any())).thenReturn(page(fine));

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
                .andExpect(jsonPath("$[0].paidAt").value(nullValue()))
                .andExpect(jsonPath("$[0].voided").value(false))
                .andExpect(jsonPath("$[0].voidedAt").value(nullValue()))
                .andExpect(jsonPath("$[0].disputed").value(false))
                .andExpect(jsonPath("$[0].disputeStatus").value(nullValue()))
                .andExpect(jsonPath("$[0].userId").doesNotExist())
                .andExpect(jsonPath("$[0].length()").value(16));
    }

    @Test
    void listItemsCarryTheDisputeStateOfTheirFine() throws Exception {
        when(fineService.byPlate(eq("ABC123"), eq(false), any())).thenReturn(page(fine));
        when(fineService.disputeStatusesOf(any())).thenReturn(Map.of(fine.getId(), DisputeStatus.OPEN));

        mvc.perform(get("/api/v1/fines/plate/ABC123").with(user("driver", List.of("ABC123"), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].disputed").value(true))
                .andExpect(jsonPath("$[0].disputeStatus").value("open"));
    }

    @Test
    void aDecidedDisputeIsReportedWithItsStatusButIsNoLongerADisputedFine() throws Exception {
        when(fineService.get(fine.getId())).thenReturn(fine);
        when(fineService.disputeStatusOf(fine.getId())).thenReturn(DisputeStatus.UPHELD);

        mvc.perform(get("/api/v1/fines/{id}", fine.getId()).with(user("driver", List.of("ABC123"), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disputed").value(false))
                .andExpect(jsonPath("$.disputeStatus").value("upheld"));
    }

    @Test
    void singleFineHasTheSameShape() throws Exception {
        when(fineService.get(fine.getId())).thenReturn(fine);

        mvc.perform(get("/api/v1/fines/{id}", fine.getId()).with(user("admin", List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("wrong_permit"))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.length()").value(16));
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
        when(fineService.byPlate(eq("ABC123"), eq(false), any())).thenReturn(page(fine));

        mvc.perform(get("/api/v1/fines/plate/abc-123").with(user("driver", List.of("ABC 123"), List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].plate").value("ABC123"));
        mvc.perform(get("/api/v1/fines/plate/XYZ999").with(user("driver", List.of("ABC123"), List.of())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(get("/api/v1/fines/plate/XYZ999").with(userWithoutFallback("driver", List.of("ABC123"), List.of())))
                .andExpect(status().isForbidden());
        verify(fineService, never()).byPlate(eq("XYZ999"), eq(false), any());
    }

    @Test
    void staffWithViolationReadMayReadAnyPlate() throws Exception {
        when(fineService.byPlate(eq("XYZ999"), eq(false), any())).thenReturn(page());

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
        verify(fineService, never()).byPlate(any(), anyBoolean(), any());
    }

    @Test
    void plateListIsNewestFirstAndBounded() throws Exception {
        when(fineService.byPlate(eq("ABC123"), eq(false), any())).thenReturn(page());

        mvc.perform(get("/api/v1/fines/plate/ABC123?page=-3&size=100000").with(user("admin", List.of(), List.of())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/fines/plate/ABC123?size=0").with(user("admin", List.of(), List.of())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/fines/plate/ABC123").with(user("admin", List.of(), List.of())))
                .andExpect(status().isOk());

        verify(fineService).byPlate(eq("ABC123"), eq(false), argThat((Pageable p) -> p.getPageSize() == 500 && p.getPageNumber() == 0
                && p.getSort().getOrderFor("issuedAt").isDescending()));
        verify(fineService).byPlate(eq("ABC123"), eq(false), argThat((Pageable p) -> p.getPageSize() == 1));
        verify(fineService).byPlate(eq("ABC123"), eq(false), argThat((Pageable p) -> p.getPageSize() == 100));
    }

    @Test
    void paidAndVoidedStateIsPartOfTheJson() throws Exception {
        Fine paid = Fine.issue(Violation.open(UUID.randomUUID(), "z1", "ABC123", OWNER, ViolationType.NO_PARKING, T, T),
                new BigDecimal("900"), OWNER, T);
        paid.markPaid(T.plusSeconds(60), PaidMethod.CASH, null);
        Fine voided = Fine.issue(Violation.open(UUID.randomUUID(), "z1", "ABC123", OWNER, ViolationType.NO_PARKING, T, T),
                new BigDecimal("900"), OWNER, T);
        voided.voidFine(T.plusSeconds(120), "misread plate", null);
        when(fineService.byPlate(eq("ABC123"), eq(true), any())).thenReturn(page(paid, voided));

        mvc.perform(get("/api/v1/fines/plate/ABC123?includeVoided=true").with(user("operator", List.of(), List.of("z1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].paid").value(true))
                .andExpect(jsonPath("$[0].paidAt").value("2026-10-09T10:01:00Z"))
                .andExpect(jsonPath("$[0].voided").value(false))
                .andExpect(jsonPath("$[1].voided").value(true))
                .andExpect(jsonPath("$[1].voidedAt").value("2026-10-09T10:02:00Z"))
                .andExpect(jsonPath("$[1].voidReason").doesNotExist())
                .andExpect(jsonPath("$[1].voidedBy").doesNotExist());
    }

    // ---- voided fines ----

    @Test
    void plateListLeavesOutVoidedFinesUnlessStaffAsksForThem() throws Exception {
        when(fineService.byPlate(any(), anyBoolean(), any())).thenReturn(page());

        mvc.perform(get("/api/v1/fines/plate/ABC123").with(user("operator", List.of(), List.of("z1")))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/fines/plate/ABC123?includeVoided=true").with(user("operator", List.of(), List.of("z1"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/fines/plate/ABC123?includeVoided=true").with(user("admin", List.of(), List.of())))
                .andExpect(status().isOk());
        // a driver can ask, the flag is ignored: drivers never see voided fines
        mvc.perform(get("/api/v1/fines/plate/ABC123?includeVoided=true").with(user("driver", List.of("ABC123"), List.of())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/fines/plate/ABC123?includeVoided=maybe").with(user("admin", List.of(), List.of())))
                .andExpect(status().isBadRequest());

        // default listing and the driver (flag ignored) leave voided fines out; both staff calls include them
        verify(fineService, times(2)).byPlate(eq("ABC123"), eq(false), any());
        verify(fineService, times(2)).byPlate(eq("ABC123"), eq(true), any());
    }

    @Test
    void zoneListPassesIncludeVoidedOnAndKeepsThePaidFilter() throws Exception {
        when(fineService.byZone(eq("z1"), any(), anyBoolean(), any())).thenReturn(page());
        var operator = user("operator", List.of(), List.of("z1"));

        mvc.perform(get("/api/v1/fines/zone/z1?includeVoided=true").with(operator)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/fines/zone/z1?paid=false&includeVoided=true").with(operator)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/fines/zone/z1?paid=false").with(operator)).andExpect(status().isOk());

        verify(fineService).byZone(eq("z1"), isNull(), eq(true), any());
        verify(fineService).byZone(eq("z1"), eq(false), eq(true), any());
        verify(fineService).byZone(eq("z1"), eq(false), eq(false), any());
    }

    @Test
    void aDriverGetsNotFoundForAVoidedFineEvenForTheirOwnPlateWhileStaffStillSeesIt() throws Exception {
        fine.voidFine(T.plusSeconds(60), "misread plate", null);
        when(fineService.get(fine.getId())).thenReturn(fine);

        mvc.perform(get("/api/v1/fines/{id}", fine.getId()).with(user("driver", List.of("ABC123"), List.of())))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        // someone else's voided fine is as invisible as an unknown one (no 403 that would confirm it exists)
        mvc.perform(get("/api/v1/fines/{id}", fine.getId()).with(user("driver", List.of("XYZ999"), List.of())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/fines/{id}", fine.getId()).with(user("operator", List.of(), List.of("z1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voided").value(true));
        mvc.perform(get("/api/v1/fines/{id}", fine.getId()).with(user("operator", List.of(), List.of("z2"))))
                .andExpect(status().isForbidden());
    }

    // ---- GET /zone/{zoneId} ----

    @Test
    void zoneListNeedsViolationReadAndTheZone() throws Exception {
        when(fineService.byZone(eq("z1"), isNull(), eq(false), any())).thenReturn(page(fine));

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
        verify(fineService, never()).byZone(any(), any(), anyBoolean(), any());
    }

    @Test
    void adminMayReadAnyZone() throws Exception {
        when(fineService.byZone(eq("zX"), isNull(), eq(false), any())).thenReturn(page());

        mvc.perform(get("/api/v1/fines/zone/zX").with(user("admin", List.of(), List.of())))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void zoneListPassesThePaidFilterAndIsBounded() throws Exception {
        when(fineService.byZone(eq("z1"), any(), eq(false), any())).thenReturn(page());
        var operator = user("operator", List.of(), List.of("z1"));

        mvc.perform(get("/api/v1/fines/zone/z1?paid=true").with(operator)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/fines/zone/z1?paid=false").with(operator)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/fines/zone/z1?page=-1&size=99999").with(operator)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/fines/zone/z1?paid=maybe").with(operator)).andExpect(status().isBadRequest());

        verify(fineService).byZone(eq("z1"), eq(true), eq(false), any());
        verify(fineService).byZone(eq("z1"), eq(false), eq(false), any());
        verify(fineService).byZone(eq("z1"), isNull(), eq(false), argThat((Pageable p) -> p.getPageSize() == 500 && p.getPageNumber() == 0
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
