package com.parkview.ruleengine.controller;

import com.parkview.ruleengine.domain.Dispute;
import com.parkview.ruleengine.domain.type.DisputeChannel;
import com.parkview.ruleengine.domain.type.DisputeStatus;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.type.ViolationType;
import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.security.AccessPolicy;
import com.parkview.ruleengine.security.CurrentUser;
import com.parkview.ruleengine.security.JwtSecurityConfig;
import com.parkview.ruleengine.service.fine.DisputeDetails;
import com.parkview.ruleengine.service.fine.DisputeQueryService;
import com.parkview.ruleengine.service.fine.DisputeService;
import com.parkview.ruleengine.service.fine.FineQueryService;
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
import java.util.Optional;
import java.util.UUID;

import static com.parkview.ruleengine.controller.WebTestSupport.ACTOR_ID;
import static com.parkview.ruleengine.controller.WebTestSupport.actor;
import static com.parkview.ruleengine.controller.WebTestSupport.token;
import static com.parkview.ruleengine.controller.WebTestSupport.user;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The dispute endpoints: who may open, read, withdraw, list and decide (anonymous, other plate, other zone, missing permission, admin), validation, conflicts. */
@WebMvcTest(DisputeController.class)
@Import({JwtSecurityConfig.class, AccessPolicy.class, CurrentUser.class, FineAccess.class})
@TestPropertySource(properties = {
        "parkview.security.issuer=http://issuer.test/auth/v1",
        "parkview.security.internal-api-key=" + WebTestSupport.INTERNAL_KEY})
class DisputeControllerTest {

    /** The access policy asks it for the site of a zone; unknown zones are their own site, like every zone from before sites. */
    @MockitoBean
    ZoneDirectory zoneDirectory;

    private static final Instant T = Instant.parse("2026-10-09T10:00:00Z");
    private static final UUID FINE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID DISPUTE_ID = UUID.fromString("99999999-2222-3333-4444-555555555555");
    private static final UUID ACTOR = UUID.fromString(ACTOR_ID);
    private static final String MESSAGE = "I was loading the car for two minutes";
    private static final String NOTE = "The delivery note proves the stop";
    private static final String BODY = "{\"message\":\"" + MESSAGE + "\"}";
    private static final String DECISION = "{\"outcome\":\"voided\",\"note\":\"" + NOTE + "\"}";
    private static final List<String> MANAGE = List.of("fine:manage");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    FineQueryService fineQuery;
    @MockitoBean
    DisputeService disputeService;
    @MockitoBean
    DisputeQueryService disputes;

    private Fine fine;
    private Dispute dispute;

    @BeforeEach
    void setUp() {
        Violation violation = Violation.open(UUID.randomUUID(), "z1", "z1", "ABC123", null, ViolationType.OVERSTAY, T, T);
        fine = Fine.issue(violation, new BigDecimal("450"), null, T);
        ReflectionTestUtils.setField(fine, "id", FINE_ID);
        dispute = Dispute.open(fine, UUID.randomUUID(), DisputeChannel.DRIVER, MESSAGE, T);
        ReflectionTestUtils.setField(dispute, "id", DISPUTE_ID);
        when(fineQuery.get(FINE_ID)).thenReturn(fine);
        when(disputes.get(DISPUTE_ID)).thenReturn(dispute);
        when(disputes.detailsOf(any(Dispute.class))).thenAnswer(inv -> new DisputeDetails(inv.getArgument(0), fine, "Storgatan 1", "Storgatan 1"));
        when(disputeService.open(eq(FINE_ID), any(), any(), any())).thenReturn(dispute);
        when(disputeService.withdraw(eq(DISPUTE_ID), any())).thenReturn(dispute);
        when(disputeService.decide(eq(DISPUTE_ID), any(), any(), any())).thenReturn(dispute);
    }

    private static RequestPostProcessor driver(String... plates) {
        return token(true, "driver", null, List.of(plates), List.of(), "7d0c2a56-6b53-4d4e-9a0c-0f9d1f4f4e11");
    }

    private static RequestPostProcessor operator(List<String> permissions, String... zones) {
        return actor("operator", permissions, List.of(zones));
    }

    // ---- POST /fines/{id}/dispute ----

    @Test
    void opening_anonymousIsUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/fines/{id}/dispute", FINE_ID).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(disputeService);
    }

    @Test
    void opening_theOwnerDriverGetsA201WithTheDisputeShapeTheWebAppReads() throws Exception {
        mvc.perform(post("/api/v1/fines/{id}/dispute", FINE_ID).with(driver("ABC123")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/fines/" + FINE_ID + "/dispute"))
                .andExpect(jsonPath("$.id").value(DISPUTE_ID.toString()))
                .andExpect(jsonPath("$.fineId").value(FINE_ID.toString()))
                .andExpect(jsonPath("$.plate").value("ABC123"))
                .andExpect(jsonPath("$.zoneId").value("z1"))
                .andExpect(jsonPath("$.zoneAddress").value("Storgatan 1"))
                .andExpect(jsonPath("$.channel").value("driver"))
                .andExpect(jsonPath("$.message").value(MESSAGE))
                .andExpect(jsonPath("$.status").value("open"))
                .andExpect(jsonPath("$.amountSek").value(450.0))
                .andExpect(jsonPath("$.reasonCode").value("overstay"))
                .andExpect(jsonPath("$.reasonText").value("The vehicle stayed longer than the time allowed here."))
                .andExpect(jsonPath("$.openedAt").value("2026-10-09T10:00:00Z"))
                .andExpect(jsonPath("$.decidedAt").value(nullValue()))
                .andExpect(jsonPath("$.decisionNote").value(nullValue()))
                .andExpect(jsonPath("$.length()").value(14));
        verify(disputeService).open(eq(FINE_ID), eq(DisputeChannel.DRIVER), any(UUID.class), eq(MESSAGE));
    }

    @Test
    void opening_aDriverOfAnotherPlateGetsA404AndNothingHappens() throws Exception {
        mvc.perform(post("/api/v1/fines/{id}/dispute", FINE_ID).with(driver("XYZ999")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        verifyNoInteractions(disputeService);
    }

    @Test
    void opening_anUnknownFineIsA404() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(fineQuery.get(unknown)).thenThrow(new ResourceNotFoundException("Fine", unknown));

        mvc.perform(post("/api/v1/fines/{id}/dispute", unknown).with(driver("ABC123")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void opening_anOperatorWithFineManageInTheZoneOpensItOnTheOperatorChannel() throws Exception {
        mvc.perform(post("/api/v1/fines/{id}/dispute", FINE_ID).with(operator(MANAGE, "z1"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());
        verify(disputeService).open(eq(FINE_ID), eq(DisputeChannel.OPERATOR), eq(ACTOR), eq(MESSAGE));
    }

    @Test
    void opening_anAdminMayOpenInAnyZone() throws Exception {
        mvc.perform(post("/api/v1/fines/{id}/dispute", FINE_ID).with(actor("admin", null, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());
        verify(disputeService).open(eq(FINE_ID), eq(DisputeChannel.OPERATOR), eq(ACTOR), eq(MESSAGE));
    }

    @Test
    void opening_anOperatorOfAnotherZoneGetsA404() throws Exception {
        mvc.perform(post("/api/v1/fines/{id}/dispute", FINE_ID).with(operator(MANAGE, "z2"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());
        verifyNoInteractions(disputeService);
    }

    @Test
    void opening_anOperatorWithoutFineManageIsForbidden() throws Exception {
        mvc.perform(post("/api/v1/fines/{id}/dispute", FINE_ID).with(operator(List.of("violation:read", "violation:resolve"), "z1"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(disputeService);
    }

    @Test
    void opening_theMessageIsTrimmedAndLengthChecked() throws Exception {
        mvc.perform(post("/api/v1/fines/{id}/dispute", FINE_ID).with(driver("ABC123")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   " + MESSAGE + "   \"}"))
                .andExpect(status().isCreated());
        verify(disputeService).open(eq(FINE_ID), any(), any(), eq(MESSAGE));

        for (String bad : List.of("{}", "{\"message\":null}", "{\"message\":\"          \"}", "{\"message\":\"too short\"}",
                "{\"message\":\"" + "x".repeat(1001) + "\"}")) {
            mvc.perform(post("/api/v1/fines/{id}/dispute", FINE_ID).with(driver("ABC123")).contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.errors[0].field").value("message"));
        }
        mvc.perform(post("/api/v1/fines/{id}/dispute", FINE_ID).with(driver("ABC123")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + "x".repeat(1000) + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void opening_aConflictIsProblemJsonWithAPlainEnglishDetail() throws Exception {
        when(disputeService.open(eq(FINE_ID), any(), any(), any()))
                .thenThrow(new ConflictException("A review of this fine has already been requested and is still open."));

        mvc.perform(post("/api/v1/fines/{id}/dispute", FINE_ID).with(driver("ABC123")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("A review of this fine has already been requested and is still open."));
    }

    @Test
    void opening_anOperatorTokenWhoseSubjectIsNotAUserIdIsForbidden() throws Exception {
        mvc.perform(post("/api/v1/fines/{id}/dispute", FINE_ID)
                        .with(token(true, "operator", MANAGE, List.of(), List.of("z1"), "not-a-uuid"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(disputeService);
    }

    // ---- GET /fines/{id}/dispute ----

    @Test
    void latest_anonymousIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/fines/{id}/dispute", FINE_ID)).andExpect(status().isUnauthorized());
    }

    @Test
    void latest_theOwnerAndOperatorsOfTheZoneSeeIt() throws Exception {
        when(disputes.latestOf(FINE_ID)).thenReturn(Optional.of(dispute));

        mvc.perform(get("/api/v1/fines/{id}/dispute", FINE_ID).with(driver("ABC123")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("open"));
        mvc.perform(get("/api/v1/fines/{id}/dispute", FINE_ID).with(operator(List.of("violation:read"), "z1")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/fines/{id}/dispute", FINE_ID).with(operator(MANAGE, "z1")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/fines/{id}/dispute", FINE_ID).with(actor("admin", null, List.of())))
                .andExpect(status().isOk());
    }

    @Test
    void latest_aDriverCanStillSeeTheOutcomeWhenTheFineWasVoidedByIt() throws Exception {
        fine.voidFine(T.plusSeconds(60), "decided", ACTOR);
        when(disputes.latestOf(FINE_ID)).thenReturn(Optional.of(dispute));

        mvc.perform(get("/api/v1/fines/{id}/dispute", FINE_ID).with(driver("ABC123"))).andExpect(status().isOk());
    }

    @Test
    void latest_otherDriversAndOtherZonesGetA404() throws Exception {
        when(disputes.latestOf(FINE_ID)).thenReturn(Optional.of(dispute));

        mvc.perform(get("/api/v1/fines/{id}/dispute", FINE_ID).with(driver("XYZ999"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/fines/{id}/dispute", FINE_ID).with(operator(List.of("violation:read"), "z2")))
                .andExpect(status().isNotFound());
    }

    @Test
    void latest_anOperatorWithoutAnyRelevantPermissionIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/fines/{id}/dispute", FINE_ID).with(operator(List.of("permit:manage"), "z1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void latest_aFineWithoutDisputeIsA404() throws Exception {
        when(disputes.latestOf(FINE_ID)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/fines/{id}/dispute", FINE_ID).with(driver("ABC123"))).andExpect(status().isNotFound());
    }

    // ---- POST /disputes/{id}/withdraw ----

    @Test
    void withdraw_anonymousIsUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/disputes/{id}/withdraw", DISPUTE_ID)).andExpect(status().isUnauthorized());
    }

    @Test
    void withdraw_theOwnerDriverWithdraws() throws Exception {
        mvc.perform(post("/api/v1/disputes/{id}/withdraw", DISPUTE_ID).with(driver("ABC123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(DISPUTE_ID.toString()));
        verify(disputeService).withdraw(eq(DISPUTE_ID), any(UUID.class));
    }

    @Test
    void withdraw_anotherDriverGetsA404AndOperatorsAreForbidden() throws Exception {
        mvc.perform(post("/api/v1/disputes/{id}/withdraw", DISPUTE_ID).with(driver("XYZ999"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/disputes/{id}/withdraw", DISPUTE_ID).with(operator(MANAGE, "z1"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/disputes/{id}/withdraw", DISPUTE_ID).with(actor("admin", null, List.of()))).andExpect(status().isForbidden());
        verify(disputeService, never()).withdraw(any(), any());
    }

    @Test
    void withdraw_aClosedDisputeIsAConflictAndAnUnknownOneA404() throws Exception {
        when(disputeService.withdraw(eq(DISPUTE_ID), any()))
                .thenThrow(new ConflictException("This dispute has been withdrawn and cannot be changed."));
        UUID unknown = UUID.randomUUID();
        when(disputes.get(unknown)).thenThrow(new ResourceNotFoundException("Dispute", unknown));

        mvc.perform(post("/api/v1/disputes/{id}/withdraw", DISPUTE_ID).with(driver("ABC123"))).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/disputes/{id}/withdraw", unknown).with(driver("ABC123"))).andExpect(status().isNotFound());
    }

    // ---- GET /disputes ----

    private void pageOfOne() {
        when(disputes.list(any(), any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(
                List.of(new DisputeDetails(dispute, fine, "Storgatan 1", "Storgatan 1")), PageRequest.of(0, 100), 1));
    }

    @Test
    void list_anonymousIsUnauthorizedAndDriversAndOperatorsWithoutFineManageAreForbidden() throws Exception {
        mvc.perform(get("/api/v1/disputes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/disputes").with(driver("ABC123"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/disputes").with(operator(List.of("violation:read"), "z1"))).andExpect(status().isForbidden());
        verifyNoInteractions(disputes);
    }

    @Test
    void list_anOperatorSeesOnlyTheZonesOfTheTokenAndTheTotalIsInTheHeader() throws Exception {
        pageOfOne();

        mvc.perform(get("/api/v1/disputes").with(operator(MANAGE, "z1", "z3")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath("$[0].id").value(DISPUTE_ID.toString()))
                .andExpect(jsonPath("$[0].message").value(MESSAGE));
        verify(disputes).list(eq(DisputeQueryService.Filter.OPEN), eq(List.of("z1", "z3")), isNull(), any(Pageable.class));
    }

    @Test
    void list_anAdminSeesEveryZone() throws Exception {
        pageOfOne();

        mvc.perform(get("/api/v1/disputes").param("status", "all").with(actor("admin", null, List.of())))
                .andExpect(status().isOk());
        verify(disputes).list(eq(DisputeQueryService.Filter.ALL), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void list_theZoneFilterMustBeAZoneOfTheCaller() throws Exception {
        pageOfOne();

        mvc.perform(get("/api/v1/disputes").param("zoneId", "z1").param("status", "decided").with(operator(MANAGE, "z1", "z3")))
                .andExpect(status().isOk());
        verify(disputes).list(eq(DisputeQueryService.Filter.DECIDED), eq(List.of("z1", "z3")), eq("z1"), any(Pageable.class));

        mvc.perform(get("/api/v1/disputes").param("zoneId", "z2").with(operator(MANAGE, "z1")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/disputes").param("zoneId", "z2").with(actor("admin", null, List.of())))
                .andExpect(status().isOk());
    }

    @Test
    void list_anUnknownStatusIsABadRequest() throws Exception {
        mvc.perform(get("/api/v1/disputes").param("status", "pending").with(operator(MANAGE, "z1")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        verify(disputes, never()).list(any(), anyCollection(), any(), any());
    }

    @Test
    void list_pagingFollowsTheOtherListsAndIsNewestFirst() throws Exception {
        pageOfOne();

        mvc.perform(get("/api/v1/disputes").param("page", "2").param("size", "9999").with(operator(MANAGE, "z1")))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<Pageable> page = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(disputes).list(any(), any(), isNull(), page.capture());
        org.assertj.core.api.Assertions.assertThat(page.getValue().getPageNumber()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(page.getValue().getPageSize()).isEqualTo(500);
        org.assertj.core.api.Assertions.assertThat(page.getValue().getSort().getOrderFor("openedAt").isDescending()).isTrue();
    }

    // ---- POST /disputes/{id}/decision ----

    @Test
    void decision_anonymousIsUnauthorizedAndNonManagersAreForbidden() throws Exception {
        mvc.perform(post("/api/v1/disputes/{id}/decision", DISPUTE_ID).contentType(MediaType.APPLICATION_JSON).content(DECISION))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/disputes/{id}/decision", DISPUTE_ID).with(driver("ABC123"))
                        .contentType(MediaType.APPLICATION_JSON).content(DECISION)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/disputes/{id}/decision", DISPUTE_ID).with(operator(List.of("violation:read"), "z1"))
                        .contentType(MediaType.APPLICATION_JSON).content(DECISION)).andExpect(status().isForbidden());
        verifyNoInteractions(disputeService);
    }

    @Test
    void decision_anOperatorOfTheZoneAndAnAdminDecide() throws Exception {
        mvc.perform(post("/api/v1/disputes/{id}/decision", DISPUTE_ID).with(operator(MANAGE, "z1"))
                        .contentType(MediaType.APPLICATION_JSON).content(DECISION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(DISPUTE_ID.toString()));
        verify(disputeService).decide(DISPUTE_ID, DisputeStatus.VOIDED, ACTOR, NOTE);

        mvc.perform(post("/api/v1/disputes/{id}/decision", DISPUTE_ID).with(actor("admin", null, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"UPHELD\",\"note\":\"  " + NOTE + "  \"}"))
                .andExpect(status().isOk());
        verify(disputeService).decide(DISPUTE_ID, DisputeStatus.UPHELD, ACTOR, NOTE);
    }

    @Test
    void decision_anOperatorOfAnotherZoneGetsA404() throws Exception {
        mvc.perform(post("/api/v1/disputes/{id}/decision", DISPUTE_ID).with(operator(MANAGE, "z2"))
                        .contentType(MediaType.APPLICATION_JSON).content(DECISION))
                .andExpect(status().isNotFound());
        verifyNoInteractions(disputeService);
    }

    @Test
    void decision_theOutcomeAndAWrittenNoteAreMandatory() throws Exception {
        for (String bad : List.of("{}", "{\"outcome\":\"voided\"}", "{\"outcome\":\"voided\",\"note\":\"\"}",
                "{\"outcome\":\"voided\",\"note\":\"too short\"}", "{\"outcome\":\"voided\",\"note\":\"" + "x".repeat(1001) + "\"}",
                "{\"outcome\":\"withdrawn\",\"note\":\"" + NOTE + "\"}", "{\"outcome\":\"maybe\",\"note\":\"" + NOTE + "\"}",
                "{\"note\":\"" + NOTE + "\"}")) {
            mvc.perform(post("/api/v1/disputes/{id}/decision", DISPUTE_ID).with(operator(MANAGE, "z1"))
                            .contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
        verifyNoInteractions(disputeService);
    }

    @Test
    void decision_decidingTwiceIsAConflictAndAnUnknownDisputeA404() throws Exception {
        when(disputeService.decide(eq(DISPUTE_ID), any(), any(), any()))
                .thenThrow(new ConflictException("This dispute has already been decided and cannot be changed."));
        UUID unknown = UUID.randomUUID();
        when(disputes.get(unknown)).thenThrow(new ResourceNotFoundException("Dispute", unknown));

        mvc.perform(post("/api/v1/disputes/{id}/decision", DISPUTE_ID).with(operator(MANAGE, "z1"))
                        .contentType(MediaType.APPLICATION_JSON).content(DECISION))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("This dispute has already been decided and cannot be changed."));
        mvc.perform(post("/api/v1/disputes/{id}/decision", unknown).with(operator(MANAGE, "z1"))
                        .contentType(MediaType.APPLICATION_JSON).content(DECISION))
                .andExpect(status().isNotFound());
    }

    @Test
    void decision_aTokenWhoseSubjectIsNotAUserIdCannotDecide() throws Exception {
        mvc.perform(post("/api/v1/disputes/{id}/decision", DISPUTE_ID)
                        .with(token(true, "operator", MANAGE, List.of(), List.of("z1"), "not-a-uuid"))
                        .contentType(MediaType.APPLICATION_JSON).content(DECISION))
                .andExpect(status().isForbidden());
        verifyNoInteractions(disputeService);
    }
}
