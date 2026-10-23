package com.parkview.ruleengine.persistence;

import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.FineEvidence;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.type.ViolationType;
import com.parkview.ruleengine.repository.FineEvidenceRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The evidence table against the real Liquibase schema: round trip, and that it can never be changed - by the entity or by SQL. */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FineEvidenceRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withInitScript("test-schema.sql");

    private static final Instant DETECTED = Instant.parse("2026-10-09T09:45:00Z");
    private static final Instant ISSUED = Instant.parse("2026-10-09T10:00:00Z");

    @Autowired
    FineEvidenceRepository evidence;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    EntityManager em;

    private UUID fineId;
    private Fine fine;
    private Violation violation;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate fine_evidence, fines cascade");
        fineId = UUID.randomUUID();
        jdbc.update("insert into fines (id, plate, zone_id, reason, amount_sek, issued_at) values (?, 'ABC123', 'z1', 'overstay', 450, ?)",
                fineId, java.sql.Timestamp.from(ISSUED));
        violation = Violation.open(UUID.randomUUID(), "z1", "ABC123", null, ViolationType.OVERSTAY, DETECTED,
                DETECTED.plus(Duration.ofMinutes(5)));
        ReflectionTestUtils.setField(violation, "id", UUID.randomUUID());
        fine = Fine.issue(violation, new BigDecimal("450"), null, ISSUED);
        ReflectionTestUtils.setField(fine, "id", fineId);
    }

    private FineEvidence save(List<String> detectionIds, List<String> images, BigDecimal confidence) {
        return tx.execute(status -> evidence.save(FineEvidence.of(fine, violation, "Storgatan 1", "A1",
                "The stay limit is 60 minutes (the limit of the zone) and the parking session had lasted longer.",
                detectionIds, images, confidence)));
    }

    @Test
    void anEvidenceRowRoundTripsWithItsArraysAndTimestamps() {
        String detection = UUID.randomUUID().toString();
        save(List.of(detection), List.of("snapshots/cam-1/a.jpg", "snapshots/cam-1/b.jpg"), new BigDecimal("0.8700"));

        FineEvidence loaded = evidence.findById(fineId).orElseThrow();

        assertThat(loaded.getPlate()).isEqualTo("ABC123");
        assertThat(loaded.getZoneId()).isEqualTo("z1");
        assertThat(loaded.getZoneAddress()).isEqualTo("Storgatan 1");
        assertThat(loaded.getSpotId()).isEqualTo(violation.getSpotId());
        assertThat(loaded.getSpotNumber()).isEqualTo("A1");
        assertThat(loaded.getViolationType()).isEqualTo("OVERSTAY");
        assertThat(loaded.getViolationId()).isEqualTo(violation.getId());
        assertThat(loaded.getGraceMinutes()).isEqualTo(5);
        assertThat(loaded.getViolationDetectedAt()).isEqualTo(DETECTED);
        assertThat(loaded.getGraceExpiredAt()).isEqualTo(DETECTED.plus(Duration.ofMinutes(5)));
        assertThat(loaded.getIssuedAt()).isEqualTo(ISSUED);
        assertThat(loaded.getDetectionIds()).containsExactly(detection);
        assertThat(loaded.getImagePaths()).containsExactly("snapshots/cam-1/a.jpg", "snapshots/cam-1/b.jpg");
        assertThat(loaded.getMinConfidence()).isEqualByComparingTo("0.87");
        assertThat(loaded.getDecisionBasis()).isEqualTo("automatic");
        assertThat(jdbc.queryForObject("select jsonb_array_length(image_paths) from fine_evidence where fine_id = ?", Integer.class, fineId)).isEqualTo(2);
    }

    @Test
    void noDetectionsAndNoConfidenceAreStoredAsEmptyArraysAndNull() {
        save(List.of(), List.of(), null);

        FineEvidence loaded = evidence.findById(fineId).orElseThrow();

        assertThat(loaded.getDetectionIds()).isEmpty();
        assertThat(loaded.getImagePaths()).isEmpty();
        assertThat(loaded.getMinConfidence()).isNull();
    }

    @Test
    void aFineWithoutEvidenceHasNoRow() {
        assertThat(evidence.findById(fineId)).isEmpty();
        assertThat(evidence.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void theDatabaseRejectsEveryUpdateAndDeleteOfAnEvidenceRow() {
        save(List.of(), List.of("snapshots/cam-1/a.jpg"), null);

        assertThatThrownBy(() -> jdbc.update("update fine_evidence set rule_summary = 'edited' where fine_id = ?", fineId))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fine_evidence is immutable");
        assertThatThrownBy(() -> jdbc.update("update fine_evidence set image_paths = '[]'::jsonb")).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("delete from fine_evidence where fine_id = ?", fineId)).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("delete from fine_evidence")).hasMessageContaining("immutable");

        assertThat(evidence.findById(fineId).orElseThrow().getRuleSummary()).startsWith("The stay limit is 60 minutes");
        assertThat(evidence.findById(fineId).orElseThrow().getImagePaths()).hasSize(1);
    }

    @Test
    void changingTheEntityNeverReachesTheDatabase() {
        save(List.of(), List.of(), null);

        tx.executeWithoutResult(status -> {
            FineEvidence managed = evidence.findById(fineId).orElseThrow();
            ReflectionTestUtils.setField(managed, "ruleSummary", "edited in memory");
            ReflectionTestUtils.setField(managed, "plate", "ZZZ999");
            em.flush();
        });

        assertThat(jdbc.queryForObject("select rule_summary from fine_evidence where fine_id = ?", String.class, fineId))
                .startsWith("The stay limit is 60 minutes");
        assertThat(jdbc.queryForObject("select plate from fine_evidence where fine_id = ?", String.class, fineId)).isEqualTo("ABC123");
    }

    @Test
    void theRepositoryOffersNoUpdateOrDeleteApi() {
        assertThat(FineEvidenceRepository.class.getMethods()).extracting(java.lang.reflect.Method::getName)
                .containsExactlyInAnyOrder("save", "findById");
    }

    @Test
    void evidenceBelongsToAnExistingFineAndPinsIt() {
        UUID unknown = UUID.randomUUID();
        ReflectionTestUtils.setField(fine, "id", unknown);
        assertThatThrownBy(() -> save(List.of(), List.of(), null)).isInstanceOf(DataIntegrityViolationException.class);

        ReflectionTestUtils.setField(fine, "id", fineId);
        save(List.of(), List.of(), null);
        assertThatThrownBy(() -> jdbc.update("delete from fines where id = ?", fineId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyAutomaticIsAKnownDecisionBasis() {
        UUID other = UUID.randomUUID();
        jdbc.update("insert into fines (id, plate, zone_id, reason, amount_sek) values (?, 'XYZ999', 'z1', 'overstay', 450)", other);

        assertThatThrownBy(() -> jdbc.update("insert into fine_evidence (fine_id, plate, zone_id, violation_type, violation_id, rule_summary, "
                + "grace_minutes, violation_detected_at, grace_expired_at, issued_at, decision_basis) "
                + "values (?, 'XYZ999', 'z1', 'OVERSTAY', gen_random_uuid(), 'x', 1, now(), now(), now(), 'manual')", other))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fine_evidence_decision_basis_check");
    }
}
