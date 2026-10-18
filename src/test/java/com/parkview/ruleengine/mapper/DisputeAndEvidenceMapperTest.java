package com.parkview.ruleengine.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkview.ruleengine.domain.Dispute;
import com.parkview.ruleengine.domain.DisputeChannel;
import com.parkview.ruleengine.domain.DisputeStatus;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.FineEvidence;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.ViolationType;
import com.parkview.ruleengine.dto.response.EvidenceResponse;
import com.parkview.ruleengine.dto.response.ExplanationResponse;
import com.parkview.ruleengine.dto.response.FineResponse;
import com.parkview.ruleengine.dto.response.InternalFineResponse;
import com.parkview.ruleengine.service.DisputeDetails;
import com.parkview.ruleengine.service.FineExplanation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DisputeAndEvidenceMapperTest {

    private static final Instant DETECTED = Instant.parse("2026-10-09T09:45:00Z");
    private static final Instant ISSUED = Instant.parse("2026-10-09T10:00:00Z");

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private Violation violation;
    private Fine fine;
    private FineEvidence evidence;

    @BeforeEach
    void setUp() {
        violation = Violation.open(UUID.randomUUID(), "z1", "ABC123", null, ViolationType.WRONG_PERMIT, DETECTED, DETECTED.plus(Duration.ofMinutes(10)));
        ReflectionTestUtils.setField(violation, "id", UUID.randomUUID());
        fine = Fine.issue(violation, new BigDecimal("700"), null, ISSUED);
        ReflectionTestUtils.setField(fine, "id", UUID.randomUUID());
        evidence = FineEvidence.of(fine, violation, "Storgatan 1", "A1", "A resident permit is required.",
                List.of(UUID.randomUUID().toString()), List.of("snapshots/cam-1/a.jpg"), new BigDecimal("0.8700"));
    }

    // ---- fines ----

    @Test
    void fineResponsesCarryTheDisputeState() {
        assertThat(FineMapper.toResponse(fine, null)).extracting(FineResponse::disputed, FineResponse::disputeStatus).containsExactly(false, null);
        assertThat(FineMapper.toResponse(fine, DisputeStatus.OPEN)).extracting(FineResponse::disputed, FineResponse::disputeStatus).containsExactly(true, "open");
        for (DisputeStatus closed : List.of(DisputeStatus.UPHELD, DisputeStatus.VOIDED, DisputeStatus.WITHDRAWN)) {
            FineResponse response = FineMapper.toResponse(fine, closed);
            assertThat(response.disputed()).as(closed.name()).isFalse();
            assertThat(response.disputeStatus()).isEqualTo(closed.wire());
        }
    }

    @Test
    void theInternalResponseSaysWhetherTheFineIsUnderDispute() {
        InternalFineResponse open = FineMapper.toInternalResponse(fine, true);
        InternalFineResponse none = FineMapper.toInternalResponse(fine, false);

        assertThat(open.disputed()).isTrue();
        assertThat(none.disputed()).isFalse();
        assertThat(open.amountSek()).isEqualByComparingTo("700");
        assertThat(open.voided()).isFalse();
    }

    // ---- disputes ----

    @Test
    void aDisputeResponseCombinesTheDisputeTheFineAndTheZoneAddress() {
        Dispute dispute = Dispute.open(fine, UUID.randomUUID(), DisputeChannel.OPERATOR, "The customer called us", ISSUED);
        ReflectionTestUtils.setField(dispute, "id", UUID.randomUUID());
        dispute.close(DisputeStatus.VOIDED, ISSUED.plusSeconds(60), UUID.randomUUID(), "The permit was valid");

        var response = DisputeMapper.toResponse(new DisputeDetails(dispute, fine, "Storgatan 1"));

        assertThat(response.id()).isEqualTo(dispute.getId());
        assertThat(response.fineId()).isEqualTo(fine.getId());
        assertThat(response.plate()).isEqualTo("ABC123");
        assertThat(response.zoneId()).isEqualTo("z1");
        assertThat(response.zoneAddress()).isEqualTo("Storgatan 1");
        assertThat(response.channel()).isEqualTo("operator");
        assertThat(response.message()).isEqualTo("The customer called us");
        assertThat(response.status()).isEqualTo("voided");
        assertThat(response.amountSek()).isEqualByComparingTo("700");
        assertThat(response.reasonCode()).isEqualTo("wrong_permit");
        assertThat(response.reasonText()).isEqualTo("The vehicle was parked in a spot that needs a permit, and no valid permit was registered for it.");
        assertThat(response.openedAt()).isEqualTo(ISSUED);
        assertThat(response.decidedAt()).isEqualTo(ISSUED.plusSeconds(60));
        assertThat(response.decisionNote()).isEqualTo("The permit was valid");
    }

    @Test
    void aReasonTextWrittenByAnotherProducerOfTheFinesTableWins() {
        ReflectionTestUtils.setField(fine, "reasonText", "Missing resident permit");
        Dispute dispute = Dispute.open(fine, null, DisputeChannel.DRIVER, "I disagree with this", ISSUED);

        assertThat(DisputeMapper.toResponse(new DisputeDetails(dispute, fine, null)).reasonText()).isEqualTo("Missing resident permit");
        assertThat(DisputeMapper.toResponse(new DisputeDetails(dispute, fine, null)).zoneAddress()).isNull();
    }

    @Test
    void everyViolationTypeHasAPlainLanguageText() {
        for (ViolationType type : ViolationType.values()) {
            assertThat(com.parkview.ruleengine.domain.ReasonTexts.of(type)).isNotBlank().endsWith(".");
        }
    }

    // ---- evidence and explanation ----

    @Test
    void theEvidenceForOperatorsHoldsTheSnapshotsAndTheDetectionIds() throws Exception {
        EvidenceResponse response = EvidenceMapper.toResponse(fine.getId(), evidence);

        JsonNode node = json.valueToTree(response);
        assertThat(node.get("recorded").asBoolean()).isTrue();
        assertThat(node.get("imagePaths").get(0).asText()).isEqualTo("snapshots/cam-1/a.jpg");
        assertThat(node.get("detectionIds")).hasSize(1);
        assertThat(node.get("minConfidence").decimalValue()).isEqualByComparingTo("0.87");
        assertThat(node.get("graceMinutes").asInt()).isEqualTo(10);
        assertThat(node.get("decisionBasis").asText()).isEqualTo("automatic");
    }

    @Test
    void aFineWithoutEvidenceIsReportedAsNotRecorded() {
        JsonNode node = json.valueToTree(EvidenceMapper.toResponse(fine.getId(), null));

        assertThat(node.fieldNames()).toIterable().containsExactlyInAnyOrder("recorded", "fineId");
        assertThat(node.get("recorded").asBoolean()).isFalse();
    }

    @Test
    void theExplanationNeverContainsSnapshotsDetectionIdsOrThePlate() {
        ExplanationResponse response = EvidenceMapper.toExplanation(new FineExplanation(fine, evidence, "Storgatan 1", DisputeStatus.OPEN));

        JsonNode node = json.valueToTree(response);
        assertThat(node.fieldNames()).toIterable().containsExactlyInAnyOrder("fineId", "issuedAt", "amountSek", "zoneAddress", "spotNumber",
                "reasonCode", "reasonText", "ruleSummary", "detectedAt", "graceMinutes", "graceExpiredAt", "disputeStatus");
        assertThat(node.toString()).doesNotContain("snapshots/cam-1").doesNotContain(evidence.getDetectionIds().get(0)).doesNotContain("ABC123");
        assertThat(response.ruleSummary()).isEqualTo("A resident permit is required.");
        assertThat(response.graceMinutes()).isEqualTo(10);
        assertThat(response.disputeStatus()).isEqualTo("open");
    }

    @Test
    void anExplanationOfAFineWithoutEvidenceKeepsWhatTheFineKnows() {
        ExplanationResponse response = EvidenceMapper.toExplanation(new FineExplanation(fine, null, "Storgatan 1", null));

        assertThat(response.amountSek()).isEqualByComparingTo("700");
        assertThat(response.issuedAt()).isEqualTo(ISSUED);
        assertThat(response.reasonCode()).isEqualTo("wrong_permit");
        assertThat(response.spotNumber()).isNull();
        assertThat(response.ruleSummary()).isNull();
        assertThat(response.detectedAt()).isNull();
        assertThat(response.graceMinutes()).isNull();
        assertThat(response.graceExpiredAt()).isNull();
        assertThat(response.disputeStatus()).isNull();
    }
}
