package com.parkview.ruleengine.service;

import com.parkview.ruleengine.client.DetectionDirectory;
import com.parkview.ruleengine.client.DetectionDirectory.Detection;
import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.domain.Dispute;
import com.parkview.ruleengine.domain.DisputeStatus;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.FineEvidence;
import com.parkview.ruleengine.domain.ParkingSpot;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.ViolationType;
import com.parkview.ruleengine.repository.DisputeRepository;
import com.parkview.ruleengine.repository.FineEvidenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What is frozen into the evidence of a fine and what a reader gets back. */
class FineEvidenceServiceTest {

    private static final Instant DETECTED = Instant.parse("2026-10-09T09:45:00Z");
    private static final Instant ISSUED = Instant.parse("2026-10-09T10:00:00Z");

    private final FineEvidenceRepository evidence = mock(FineEvidenceRepository.class);
    private final DetectionDirectory detections = mock(DetectionDirectory.class);
    private final ZoneDirectory zones = mock(ZoneDirectory.class);
    private final SpotCacheService spots = mock(SpotCacheService.class);
    private final DisputeRepository disputes = mock(DisputeRepository.class);
    private final FineEvidenceService service = new FineEvidenceService(evidence, detections, zones, spots, disputes);

    private final ParkingSpot spot = TestFixtures.spot("permit", "resident", 0, 30);
    private Violation violation;
    private Fine fine;

    @BeforeEach
    void setUp() {
        violation = Violation.open(spot.id(), TestFixtures.ZONE, TestFixtures.PLATE, null, ViolationType.WRONG_PERMIT, DETECTED,
                DETECTED.plus(Duration.ofMinutes(10)), "No valid permit was registered for the plate: the spot requires a resident permit.");
        ReflectionTestUtils.setField(violation, "id", UUID.randomUUID());
        fine = Fine.issue(violation, new BigDecimal("700"), null, ISSUED);
        ReflectionTestUtils.setField(fine, "id", UUID.randomUUID());
        when(zones.addressOf(TestFixtures.ZONE)).thenReturn(Optional.of("Storgatan 1"));
        when(spots.spotsOf(TestFixtures.ZONE)).thenReturn(List.of(spot));
        when(detections.involvedIn(any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        when(evidence.save(any(FineEvidence.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private FineEvidence recorded() {
        return service.record(fine, violation);
    }

    @Test
    void theEvidenceHoldsTheFactsOfTheFineAndTheViolationItWasIssuedFor() {
        FineEvidence e = recorded();

        assertThat(e.getFineId()).isEqualTo(fine.getId());
        assertThat(e.getPlate()).isEqualTo(TestFixtures.PLATE);
        assertThat(e.getZoneId()).isEqualTo(TestFixtures.ZONE);
        assertThat(e.getZoneAddress()).isEqualTo("Storgatan 1");
        assertThat(e.getSpotId()).isEqualTo(spot.id());
        assertThat(e.getSpotNumber()).isEqualTo("A1");
        assertThat(e.getViolationType()).isEqualTo("WRONG_PERMIT");
        assertThat(e.getViolationId()).isEqualTo(violation.getId());
        assertThat(e.getRuleSummary()).isEqualTo("No valid permit was registered for the plate: the spot requires a resident permit.");
        assertThat(e.getGraceMinutes()).isEqualTo(10);
        assertThat(e.getViolationDetectedAt()).isEqualTo(DETECTED);
        assertThat(e.getGraceExpiredAt()).isEqualTo(DETECTED.plus(Duration.ofMinutes(10)));
        assertThat(e.getIssuedAt()).isEqualTo(ISSUED);
        assertThat(e.getDecisionBasis()).isEqualTo("automatic");
        assertThat(e.getDetectionIds()).isEmpty();
        assertThat(e.getImagePaths()).isEmpty();
        assertThat(e.getMinConfidence()).isNull();
        verify(evidence).save(e);
    }

    @Test
    void snapshotsAndTheLowestConfidenceComeFromTheDetectionsInvolved() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID typedIn = UUID.randomUUID();
        when(detections.involvedIn(eq(TestFixtures.PLATE), eq(TestFixtures.ZONE), any(), any(), anyInt())).thenReturn(List.of(
                new Detection(second, "snapshots/cam-1/b.jpg", new BigDecimal("0.91"), ISSUED.minusSeconds(60)),
                new Detection(typedIn, null, null, ISSUED.minusSeconds(120)),
                new Detection(first, "snapshots/cam-1/a.jpg", new BigDecimal("0.8700"), ISSUED.minusSeconds(180))));

        FineEvidence e = recorded();

        assertThat(e.getDetectionIds()).containsExactly(second.toString(), typedIn.toString(), first.toString());
        assertThat(e.getImagePaths()).containsExactly("snapshots/cam-1/b.jpg", "snapshots/cam-1/a.jpg");
        assertThat(e.getMinConfidence()).isEqualByComparingTo("0.87");
    }

    @Test
    void theDetectionLookupWindowStartsBeforeTheViolationAndEndsWhenTheFineIsIssued() {
        recorded();

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(detections).involvedIn(eq(TestFixtures.PLATE), eq(TestFixtures.ZONE), from.capture(), to.capture(),
                eq(FineEvidenceService.MAX_DETECTIONS));
        assertThat(from.getValue()).isEqualTo(DETECTED.minus(FineEvidenceService.LOOKBACK));
        assertThat(to.getValue()).isEqualTo(ISSUED);
    }

    @Test
    void aViolationWithoutARuleTextFallsBackToThePlainTextOfItsType() {
        Violation old = Violation.open(spot.id(), TestFixtures.ZONE, TestFixtures.PLATE, null, ViolationType.OVERSTAY, DETECTED, DETECTED);
        ReflectionTestUtils.setField(old, "id", UUID.randomUUID());

        FineEvidence e = service.record(fine, old);

        assertThat(e.getRuleSummary()).isEqualTo("The vehicle stayed longer than the time allowed here.");
    }

    @Test
    void aSpotThatNoLongerExistsLeavesTheSpotNumberOutButNotTheEvidence() {
        when(spots.spotsOf(TestFixtures.ZONE)).thenReturn(List.of());

        FineEvidence e = recorded();

        assertThat(e.getSpotNumber()).isNull();
        assertThat(e.getSpotId()).isEqualTo(spot.id());
    }

    @Test
    void anUnknownZoneLeavesTheAddressOut() {
        when(zones.addressOf(TestFixtures.ZONE)).thenReturn(Optional.empty());

        assertThat(recorded().getZoneAddress()).isNull();
    }

    // ---- reading ----

    @Test
    void findReturnsTheFrozenEvidenceOrEmptyForOldFines() {
        FineEvidence e = recorded();
        when(evidence.findById(fine.getId())).thenReturn(Optional.of(e));

        assertThat(service.find(fine.getId())).containsSame(e);
        assertThat(service.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void theExplanationUsesTheFrozenAddressAndTheStatusOfTheLatestDispute() {
        FineEvidence e = recorded();
        when(zones.addressOf(TestFixtures.ZONE)).thenReturn(Optional.of("Renamed street 9"));
        when(evidence.findById(fine.getId())).thenReturn(Optional.of(e));
        Dispute dispute = Dispute.open(fine, UUID.randomUUID(), com.parkview.ruleengine.domain.DisputeChannel.DRIVER, "I disagree with this", ISSUED);
        when(disputes.findFirstByFineIdOrderByOpenedAtDescIdDesc(fine.getId())).thenReturn(Optional.of(dispute));

        FineExplanation explanation = service.explain(fine);

        assertThat(explanation.zoneAddress()).isEqualTo("Storgatan 1");
        assertThat(explanation.evidence()).isSameAs(e);
        assertThat(explanation.disputeStatus()).isEqualTo(DisputeStatus.OPEN);
    }

    @Test
    void anOldFineWithoutEvidenceIsExplainedFromTheFineAndTheCurrentZone() {
        FineExplanation explanation = service.explain(fine);

        assertThat(explanation.evidence()).isNull();
        assertThat(explanation.zoneAddress()).isEqualTo("Storgatan 1");
        assertThat(explanation.disputeStatus()).isNull();
    }
}
