package com.parkview.ruleengine.controller;

import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.FineAction;
import com.parkview.ruleengine.domain.FineActionType;
import com.parkview.ruleengine.domain.PaidMethod;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.ViolationType;
import com.parkview.ruleengine.security.AccessPolicy;
import com.parkview.ruleengine.security.CurrentUser;
import com.parkview.ruleengine.security.JwtSecurityConfig;
import com.parkview.ruleengine.service.FineService;
import com.parkview.ruleengine.web.ConflictException;
import com.parkview.ruleengine.web.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.parkview.ruleengine.controller.WebTestSupport.ACTOR_ID;
import static com.parkview.ruleengine.controller.WebTestSupport.actor;
import static com.parkview.ruleengine.controller.WebTestSupport.token;
import static com.parkview.ruleengine.controller.WebTestSupport.user;
import static com.parkview.ruleengine.controller.WebTestSupport.userWith;
import static com.parkview.ruleengine.controller.WebTestSupport.userWithoutFallback;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Void, mark-paid and the audit trail: permission fine:manage / violation:read, zone scope, validation, conflicts. */
@WebMvcTest(FineController.class)
@Import({JwtSecurityConfig.class, AccessPolicy.class, CurrentUser.class})
@TestPropertySource(properties = {
        "parkview.security.issuer=http://issuer.test/auth/v1",
        "parkview.security.internal-api-key=" + WebTestSupport.INTERNAL_KEY})
class FineManagementControllerTest {

    private static final Instant T = Instant.parse("2026-10-09T10:00:00Z");
    private static final UUID OWNER = UUID.fromString("7d0c2a56-6b53-4d4e-9a0c-0f9d1f4f4e11");
    private static final UUID FINE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ACTOR = UUID.fromString(ACTOR_ID);
    private static final String VOID_BODY = "{\"reason\":\"Plate misread by the camera\"}";
    private static final String PAID_BODY = "{\"reason\":\"Paid at the counter\",\"method\":\"cash\"}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    FineService fineService;

    private Fine fine;

    @BeforeEach
    void setUp() {
        Violation violation = Violation.open(UUID.randomUUID(), "z1", "ABC123", OWNER, ViolationType.WRONG_PERMIT, T, T);
        fine = Fine.issue(violation, new BigDecimal("700"), OWNER, T);
        ReflectionTestUtils.setField(fine, "id", FINE_ID);
        when(fineService.get(FINE_ID)).thenReturn(fine);
        when(fineService.voidFine(eq(FINE_ID), anyString(), any())).thenAnswer(inv -> {
            fine.voidFine(T.plusSeconds(60), inv.getArgument(1), inv.getArgument(2));
            return fine;
        });
        when(fineService.markPaidManually(eq(FINE_ID), anyString(), any(), any())).thenAnswer(inv -> {
            fine.markPaid(T.plusSeconds(60), inv.getArgument(2), inv.getArgument(3));
            return fine;
        });
    }

    private static List<RequestPostProcessor> allowedToManage() {
        return List.of(
                actor("admin", null, List.of()),
                actor("operator", List.of("fine:manage"), List.of("z1")),
                actor("operator", List.of("violation:read", "fine:manage"), List.of("z1", "z2")),
                token(false, "admin", List.of("fine:manage"), List.of(), List.of(), ACTOR_ID));
    }

    private static List<RequestPostProcessor> notAllowedToManage() {
        return List.of(
                // an operator holds no fine:manage by default (it is an admin default)
                actor("operator", null, List.of("z1")),
                actor("operator", List.of("violation:read", "violation:resolve", "permit:manage"), List.of("z1")),
                actor("operator", List.of(), List.of("z1")),
                // fine:manage but not the fine's zone
                actor("operator", List.of("fine:manage"), List.of("z2")),
                actor("operator", List.of("fine:manage"), List.of()),
                // no permissions claim and no fallback
                token(false, "admin", null, List.of(), List.of(), ACTOR_ID),
                token(false, "operator", null, List.of(), List.of("z1"), ACTOR_ID),
                // drivers and unknown roles never manage fines, whatever the claims say
                token(true, "driver", List.of("fine:manage"), List.of("ABC123"), List.of("z1"), ACTOR_ID),
                user("driver", List.of("ABC123"), List.of()),
                token(true, "hacker", List.of("fine:manage"), List.of(), List.of("z1"), ACTOR_ID));
    }

    // ---- authorization matrix: POST /{id}/void and /{id}/mark-paid ----

    @Test
    void anonymousIsUnauthorizedWithProblemJson() throws Exception {
        for (String url : List.of("/void", "/mark-paid")) {
            mvc.perform(post("/api/v1/fines/{id}" + url, FINE_ID).contentType(MediaType.APPLICATION_JSON).content(PAID_BODY))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
        mvc.perform(get("/api/v1/fines/{id}/history", FINE_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        verify(fineService, never()).voidFine(any(), any(), any());
        verify(fineService, never()).markPaidManually(any(), any(), any(), any());
    }

    @Test
    void voidNeedsFineManageAndTheZoneOfTheFine() throws Exception {
        for (var allowed : allowedToManage()) {
            mvc.perform(post("/api/v1/fines/{id}/void", FINE_ID).with(allowed)
                            .contentType(MediaType.APPLICATION_JSON).content(VOID_BODY))
                    .andExpect(status().isOk());
        }
        for (var denied : notAllowedToManage()) {
            mvc.perform(post("/api/v1/fines/{id}/void", FINE_ID).with(denied)
                            .contentType(MediaType.APPLICATION_JSON).content(VOID_BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
        verify(fineService, org.mockito.Mockito.times(allowedToManage().size())).voidFine(eq(FINE_ID), anyString(), any());
    }

    @Test
    void markPaidNeedsFineManageAndTheZoneOfTheFine() throws Exception {
        for (var allowed : allowedToManage()) {
            mvc.perform(post("/api/v1/fines/{id}/mark-paid", FINE_ID).with(allowed)
                            .contentType(MediaType.APPLICATION_JSON).content(PAID_BODY))
                    .andExpect(status().isOk());
        }
        for (var denied : notAllowedToManage()) {
            mvc.perform(post("/api/v1/fines/{id}/mark-paid", FINE_ID).with(denied)
                            .contentType(MediaType.APPLICATION_JSON).content(PAID_BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
        verify(fineService, org.mockito.Mockito.times(allowedToManage().size()))
                .markPaidManually(eq(FINE_ID), anyString(), any(), any());
    }

    @Test
    void anAdminMayManageAFineOfAnyZone() throws Exception {
        ReflectionTestUtils.setField(fine, "zoneId", "zone-nobody-listed");

        mvc.perform(post("/api/v1/fines/{id}/void", FINE_ID).with(actor("admin", null, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content(VOID_BODY))
                .andExpect(status().isOk());
    }

    @Test
    void aTokenWhoseSubjectIsNotAUserIdCannotRecordAnAction() throws Exception {
        mvc.perform(post("/api/v1/fines/{id}/void", FINE_ID).with(user("admin", List.of(), List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content(VOID_BODY))
                .andExpect(status().isForbidden());
        verify(fineService, never()).voidFine(any(), any(), any());
    }

    // ---- the commands ----

    @Test
    void voidPassesTheTrimmedReasonAndTheTokenSubjectAndReturnsTheFine() throws Exception {
        mvc.perform(post("/api/v1/fines/{id}/void", FINE_ID).with(actor("admin", null, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  Plate misread  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(FINE_ID.toString()))
                .andExpect(jsonPath("$.paid").value(false))
                .andExpect(jsonPath("$.voided").value(true))
                .andExpect(jsonPath("$.voidedAt").value("2026-10-09T10:01:00Z"))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.voidReason").doesNotExist())
                .andExpect(jsonPath("$.length()").value(14));

        verify(fineService).voidFine(FINE_ID, "Plate misread", ACTOR);
    }

    @Test
    void markPaidPassesReasonMethodAndTheTokenSubjectAndReturnsTheFine() throws Exception {
        mvc.perform(post("/api/v1/fines/{id}/mark-paid", FINE_ID).with(actor("admin", null, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" Bank transfer \",\"method\":\"transfer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid").value(true))
                .andExpect(jsonPath("$.paidAt").value("2026-10-09T10:01:00Z"))
                .andExpect(jsonPath("$.voided").value(false))
                .andExpect(jsonPath("$.length()").value(14));

        verify(fineService).markPaidManually(FINE_ID, "Bank transfer", PaidMethod.TRANSFER, ACTOR);
    }

    @Test
    void everyManualMethodIsAccepted() throws Exception {
        for (String method : List.of("cash", "transfer", "other")) {
            mvc.perform(post("/api/v1/fines/{id}/mark-paid", FINE_ID).with(actor("admin", null, List.of()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Paid by hand\",\"method\":\"" + method + "\"}"))
                    .andExpect(status().isOk());
        }
        verify(fineService).markPaidManually(FINE_ID, "Paid by hand", PaidMethod.CASH, ACTOR);
        verify(fineService).markPaidManually(FINE_ID, "Paid by hand", PaidMethod.TRANSFER, ACTOR);
        verify(fineService).markPaidManually(FINE_ID, "Paid by hand", PaidMethod.OTHER, ACTOR);
    }

    @Test
    void unknownFineIsNotFoundForBothCommandsAndTheHistory() throws Exception {
        UUID id = UUID.randomUUID();
        when(fineService.get(id)).thenThrow(new ResourceNotFoundException("Fine", id));
        var admin = actor("admin", null, List.of());

        mvc.perform(post("/api/v1/fines/{id}/void", id).with(admin).contentType(MediaType.APPLICATION_JSON).content(VOID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(post("/api/v1/fines/{id}/mark-paid", id).with(admin).contentType(MediaType.APPLICATION_JSON).content(PAID_BODY))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/fines/{id}/history", id).with(admin)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/fines/not-a-uuid/void").with(admin).contentType(MediaType.APPLICATION_JSON).content(VOID_BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void conflictsFromTheServiceAreProblemJson409() throws Exception {
        when(fineService.voidFine(eq(FINE_ID), anyString(), any())).thenThrow(new ConflictException("A paid fine cannot be voided"));
        when(fineService.markPaidManually(eq(FINE_ID), anyString(), any(), any()))
                .thenThrow(new ConflictException("A voided fine cannot be marked as paid"));
        var admin = actor("admin", null, List.of());

        mvc.perform(post("/api/v1/fines/{id}/void", FINE_ID).with(admin).contentType(MediaType.APPLICATION_JSON).content(VOID_BODY))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.message").value("A paid fine cannot be voided"));
        mvc.perform(post("/api/v1/fines/{id}/mark-paid", FINE_ID).with(admin).contentType(MediaType.APPLICATION_JSON).content(PAID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A voided fine cannot be marked as paid"));
    }

    // ---- validation ----

    @Test
    void voidRejectsAnInvalidReason() throws Exception {
        var admin = actor("admin", null, List.of());
        for (String body : List.of("{}", "{\"reason\":null}", "{\"reason\":\"\"}", "{\"reason\":\"   \"}", "{\"reason\":\"ab\"}",
                "{\"reason\":\"  ab  \"}", "{\"reason\":\"" + "x".repeat(301) + "\"}")) {
            mvc.perform(post("/api/v1/fines/{id}/void", FINE_ID).with(admin).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.errors[0].field").value("reason"));
        }
        mvc.perform(post("/api/v1/fines/{id}/void", FINE_ID).with(admin).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        verify(fineService, never()).voidFine(any(), any(), any());
    }

    @Test
    void reasonLengthBoundariesAreInclusive() throws Exception {
        var admin = actor("admin", null, List.of());

        mvc.perform(post("/api/v1/fines/{id}/void", FINE_ID).with(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"abc\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/fines/{id}/void", FINE_ID).with(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + "x".repeat(300) + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void markPaidRejectsAnInvalidReasonOrMethod() throws Exception {
        var admin = actor("admin", null, List.of());
        for (String body : List.of(
                "{}", "{\"method\":\"cash\"}", "{\"reason\":\"Paid\"}", "{\"reason\":\"ab\",\"method\":\"cash\"}",
                "{\"reason\":\"Paid by hand\",\"method\":\"\"}", "{\"reason\":\"Paid by hand\",\"method\":\"card\"}",
                "{\"reason\":\"Paid by hand\",\"method\":\"online\"}", "{\"reason\":\"Paid by hand\",\"method\":\"CASH\"}",
                "{\"reason\":\"" + "x".repeat(301) + "\",\"method\":\"cash\"}")) {
            mvc.perform(post("/api/v1/fines/{id}/mark-paid", FINE_ID).with(admin).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.errors").isNotEmpty());
        }
        verify(fineService, never()).markPaidManually(any(), any(), any(), any());
    }

    // ---- GET /{id}/history ----

    @Test
    void historyNeedsViolationReadAndTheZoneOfTheFine() throws Exception {
        when(fineService.history(eq(FINE_ID), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        for (var allowed : List.of(user("operator", List.of(), List.of("z1")), user("admin", List.of(), List.of()),
                userWith("operator", List.of("violation:read"), List.of(), List.of("z1")),
                token(false, "admin", List.of("violation:read"), List.of(), List.of()))) {
            mvc.perform(get("/api/v1/fines/{id}/history", FINE_ID).with(allowed))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Total-Count", "0"));
        }
        for (var denied : List.of(
                userWith("operator", List.of("fine:manage", "violation:resolve"), List.of(), List.of("z1")),
                userWith("operator", List.of(), List.of(), List.of("z1")), userWithoutFallback("operator", List.of(), List.of("z1")),
                userWith("operator", List.of("violation:read"), List.of(), List.of("z2")), user("operator", List.of(), List.of("z2")),
                user("driver", List.of("ABC123"), List.of("z1")), user("hacker", List.of(), List.of("z1")))) {
            mvc.perform(get("/api/v1/fines/{id}/history", FINE_ID).with(denied))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
    }

    @Test
    void historyJsonHasExactlyTheDocumentedFieldsNewestFirstAndBounded() throws Exception {
        UUID entryId = UUID.fromString("99999999-8888-7777-6666-555555555555");
        FineAction voidEntry = FineAction.of(FINE_ID, T.plusSeconds(60), ACTOR, FineActionType.VOID, "Plate misread", Map.of());
        FineAction online = FineAction.of(FINE_ID, T, null, FineActionType.PAID_ONLINE, null,
                Map.of("method", "online", "paymentId", "p-1"));
        ReflectionTestUtils.setField(voidEntry, "id", entryId);
        when(fineService.history(eq(FINE_ID), any())).thenReturn(new PageImpl<>(List.of(voidEntry, online), PageRequest.of(0, 100), 2));

        mvc.perform(get("/api/v1/fines/{id}/history?page=-1&size=99999", FINE_ID).with(user("operator", List.of(), List.of("z1"))))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "2"))
                .andExpect(jsonPath("$[0].id").value(entryId.toString()))
                .andExpect(jsonPath("$[0].at").value("2026-10-09T10:01:00Z"))
                .andExpect(jsonPath("$[0].actorId").value(ACTOR_ID))
                .andExpect(jsonPath("$[0].action").value("void"))
                .andExpect(jsonPath("$[0].reason").value("Plate misread"))
                .andExpect(jsonPath("$[0].details").isMap())
                .andExpect(jsonPath("$[0].length()").value(6))
                .andExpect(jsonPath("$[1].action").value("paid_online"))
                .andExpect(jsonPath("$[1].actorId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[1].reason").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[1].details.paymentId").value("p-1"));

        verify(fineService).history(eq(FINE_ID), argThat((Pageable p) -> p.getPageSize() == 500 && p.getPageNumber() == 0
                && p.getSort().getOrderFor("at").isDescending()));
    }
}
