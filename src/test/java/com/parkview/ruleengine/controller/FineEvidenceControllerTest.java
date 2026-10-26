package com.parkview.ruleengine.controller;

import com.parkview.ruleengine.domain.type.DisputeStatus;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.FineEvidence;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.type.ViolationType;
import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.security.AccessPolicy;
import com.parkview.ruleengine.security.CurrentUser;
import com.parkview.ruleengine.security.JwtSecurityConfig;
import com.parkview.ruleengine.service.fine.FineEvidenceService;
import com.parkview.ruleengine.service.fine.FineExplanation;
import com.parkview.ruleengine.service.fine.FineQueryService;
import com.parkview.ruleengine.web.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.parkview.ruleengine.controller.WebTestSupport.actor;
import static com.parkview.ruleengine.controller.WebTestSupport.token;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Evidence (operators, with snapshots) and explanation (driver and operators, plain language only). */
@WebMvcTest(FineEvidenceController.class)
@Import({JwtSecurityConfig.class, AccessPolicy.class, CurrentUser.class, FineAccess.class})
@TestPropertySource(properties = {
        "parkview.security.issuer=http://issuer.test/auth/v1",
        "parkview.security.internal-api-key=" + WebTestSupport.INTERNAL_KEY})
class FineEvidenceControllerTest {

    /** The access policy asks it for the site of a zone; unknown zones are their own site, like every zone from before sites. */
    @MockitoBean
    ZoneDirectory zoneDirectory;

    private static final Instant DETECTED = Instant.parse("2026-10-09T09:45:00Z");
    private static final Instant ISSUED = Instant.parse("2026-10-09T10:00:00Z");
    private static final UUID FINE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID DETECTION_ID = UUID.fromString("aaaaaaaa-2222-3333-4444-555555555555");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    FineQueryService fineQuery;
    @MockitoBean
    FineEvidenceService evidence;

    private Fine fine;
    private FineEvidence frozen;

    @BeforeEach
    void setUp() {
        Violation violation = Violation.open(UUID.randomUUID(), "z1", "z1", "ABC123", null, ViolationType.OVERSTAY, DETECTED,
                DETECTED.plus(Duration.ofMinutes(5)), "The stay limit is 60 minutes (the limit of the zone) and the parking session had lasted longer.");
        ReflectionTestUtils.setField(violation, "id", UUID.randomUUID());
        fine = Fine.issue(violation, new BigDecimal("450"), null, ISSUED);
        ReflectionTestUtils.setField(fine, "id", FINE_ID);
        frozen = FineEvidence.of(fine, violation, "Storgatan 1", "Storgatan 1", "A1", violation.getRuleSummary(), List.of(DETECTION_ID.toString()),
                List.of("snapshots/cam-1/a.jpg"), new BigDecimal("0.8700"));
        when(fineQuery.get(FINE_ID)).thenReturn(fine);
        when(evidence.find(FINE_ID)).thenReturn(Optional.of(frozen));
        when(evidence.explain(any(Fine.class))).thenReturn(new FineExplanation(fine, frozen, "Storgatan 1", "Storgatan 1", DisputeStatus.OPEN));
    }

    private static RequestPostProcessor driver(String... plates) {
        return token(true, "driver", null, List.of(plates), List.of(), "7d0c2a56-6b53-4d4e-9a0c-0f9d1f4f4e11");
    }

    private static RequestPostProcessor operator(List<String> permissions, String... zones) {
        return actor("operator", permissions, List.of(zones));
    }

    // ---- evidence ----

    @Test
    void evidence_anonymousIsUnauthorizedAndDriversAndOperatorsWithoutViolationReadAreForbidden() throws Exception {
        mvc.perform(get("/api/v1/fines/{id}/evidence", FINE_ID)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/fines/{id}/evidence", FINE_ID).with(driver("ABC123"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/fines/{id}/evidence", FINE_ID).with(operator(List.of("fine:manage"), "z1"))).andExpect(status().isForbidden());
        verifyNoInteractions(evidence);
    }

    @Test
    void evidence_anOperatorOfTheZoneGetsEverythingIncludingTheSnapshotKeys() throws Exception {
        mvc.perform(get("/api/v1/fines/{id}/evidence", FINE_ID).with(operator(List.of("violation:read"), "z1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recorded").value(true))
                .andExpect(jsonPath("$.fineId").value(FINE_ID.toString()))
                .andExpect(jsonPath("$.plate").value("ABC123"))
                .andExpect(jsonPath("$.zoneId").value("z1"))
                .andExpect(jsonPath("$.zoneAddress").value("Storgatan 1"))
                .andExpect(jsonPath("$.spotNumber").value("A1"))
                .andExpect(jsonPath("$.violationType").value("OVERSTAY"))
                .andExpect(jsonPath("$.ruleSummary").value("The stay limit is 60 minutes (the limit of the zone) and the parking session had lasted longer."))
                .andExpect(jsonPath("$.graceMinutes").value(5))
                .andExpect(jsonPath("$.violationDetectedAt").value("2026-10-09T09:45:00Z"))
                .andExpect(jsonPath("$.graceExpiredAt").value("2026-10-09T09:50:00Z"))
                .andExpect(jsonPath("$.issuedAt").value("2026-10-09T10:00:00Z"))
                .andExpect(jsonPath("$.minConfidence").value(0.87))
                .andExpect(jsonPath("$.decisionBasis").value("automatic"))
                .andExpect(jsonPath("$.detectionIds[0]").value(DETECTION_ID.toString()))
                .andExpect(jsonPath("$.imagePaths[0]").value("snapshots/cam-1/a.jpg"))
                .andExpect(jsonPath("$.length()").value(17));
    }

    @Test
    void evidence_anAdminMayReadAnyZoneAndMinConfidenceIsExplicitNullWhenNoneWasRecorded() throws Exception {
        Violation v = Violation.open(frozen.getSpotId(), "z9", "z9", "ABC123", null, ViolationType.NO_PARKING, DETECTED, DETECTED);
        ReflectionTestUtils.setField(v, "id", UUID.randomUUID());
        Fine other = Fine.issue(v, new BigDecimal("900"), null, ISSUED);
        UUID otherId = UUID.randomUUID();
        ReflectionTestUtils.setField(other, "id", otherId);
        when(fineQuery.get(otherId)).thenReturn(other);
        when(evidence.find(otherId)).thenReturn(Optional.of(FineEvidence.of(other, v, null, null, null, "x", List.of(), List.of(), null)));

        mvc.perform(get("/api/v1/fines/{id}/evidence", otherId).with(actor("admin", null, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minConfidence").value(nullValue()))
                .andExpect(jsonPath("$.imagePaths").isEmpty());
    }

    @Test
    void evidence_aFineOfAnotherZoneAndAnUnknownFineAreBothA404() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(fineQuery.get(unknown)).thenThrow(new ResourceNotFoundException("Fine", unknown));

        mvc.perform(get("/api/v1/fines/{id}/evidence", FINE_ID).with(operator(List.of("violation:read"), "z2")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(get("/api/v1/fines/{id}/evidence", unknown).with(operator(List.of("violation:read"), "z1")))
                .andExpect(status().isNotFound());
        verifyNoInteractions(evidence);
    }

    @Test
    void evidence_anOldFineWithoutEvidenceAnswersRecordedFalseAndNothingElse() throws Exception {
        when(evidence.find(FINE_ID)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/fines/{id}/evidence", FINE_ID).with(operator(List.of("violation:read"), "z1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recorded").value(false))
                .andExpect(jsonPath("$.fineId").value(FINE_ID.toString()))
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ---- explanation ----

    @Test
    void explanation_anonymousIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/fines/{id}/explanation", FINE_ID)).andExpect(status().isUnauthorized());
    }

    @Test
    void explanation_theOwnerDriverGetsPlainLanguageWithoutSnapshotsIdsOrPlate() throws Exception {
        mvc.perform(get("/api/v1/fines/{id}/explanation", FINE_ID).with(driver("ABC123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fineId").value(FINE_ID.toString()))
                .andExpect(jsonPath("$.issuedAt").value("2026-10-09T10:00:00Z"))
                .andExpect(jsonPath("$.amountSek").value(450.0))
                .andExpect(jsonPath("$.zoneAddress").value("Storgatan 1"))
                .andExpect(jsonPath("$.spotNumber").value("A1"))
                .andExpect(jsonPath("$.reasonCode").value("overstay"))
                .andExpect(jsonPath("$.reasonText").value("The vehicle stayed longer than the time allowed here."))
                .andExpect(jsonPath("$.ruleSummary").value("The stay limit is 60 minutes (the limit of the zone) and the parking session had lasted longer."))
                .andExpect(jsonPath("$.detectedAt").value("2026-10-09T09:45:00Z"))
                .andExpect(jsonPath("$.graceMinutes").value(5))
                .andExpect(jsonPath("$.graceExpiredAt").value("2026-10-09T09:50:00Z"))
                .andExpect(jsonPath("$.disputeStatus").value("open"))
                .andExpect(jsonPath("$.imagePaths").doesNotExist())
                .andExpect(jsonPath("$.detectionIds").doesNotExist())
                .andExpect(jsonPath("$.plate").doesNotExist())
                .andExpect(jsonPath("$.minConfidence").doesNotExist())
                .andExpect(jsonPath("$.length()").value(12));
    }

    @Test
    void explanation_operatorsOfTheZoneWithViolationReadAndAdminsSeeTheSame() throws Exception {
        mvc.perform(get("/api/v1/fines/{id}/explanation", FINE_ID).with(operator(List.of("violation:read"), "z1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imagePaths").doesNotExist());
        mvc.perform(get("/api/v1/fines/{id}/explanation", FINE_ID).with(actor("admin", null, List.of())))
                .andExpect(status().isOk());
    }

    @Test
    void explanation_otherDriversAndOtherZonesGetA404AndAnOperatorWithoutViolationReadIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/fines/{id}/explanation", FINE_ID).with(driver("XYZ999"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/fines/{id}/explanation", FINE_ID).with(operator(List.of("violation:read"), "z2"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/fines/{id}/explanation", FINE_ID).with(operator(List.of("fine:manage"), "z1"))).andExpect(status().isForbidden());
    }

    @Test
    void explanation_anOldFineWithoutEvidenceHasNullsWhereNothingWasRecorded() throws Exception {
        when(evidence.explain(any(Fine.class))).thenReturn(new FineExplanation(fine, null, "Storgatan 1", "Storgatan 1", null));

        mvc.perform(get("/api/v1/fines/{id}/explanation", FINE_ID).with(driver("ABC123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spotNumber").value(nullValue()))
                .andExpect(jsonPath("$.ruleSummary").value(nullValue()))
                .andExpect(jsonPath("$.detectedAt").value(nullValue()))
                .andExpect(jsonPath("$.graceMinutes").value(nullValue()))
                .andExpect(jsonPath("$.graceExpiredAt").value(nullValue()))
                .andExpect(jsonPath("$.disputeStatus").value(nullValue()))
                .andExpect(jsonPath("$.zoneAddress").value("Storgatan 1"))
                .andExpect(jsonPath("$.length()").value(12));
    }
}
