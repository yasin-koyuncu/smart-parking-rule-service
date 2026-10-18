package com.parkview.ruleengine.service;

import com.parkview.ruleengine.client.DetectionDirectory;
import com.parkview.ruleengine.client.PlateOwnerDirectory;
import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.config.RuleEngineProperties;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.ParkingSpot;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.ViolationType;
import com.parkview.ruleengine.repository.ViolationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * The real issuing transaction against Postgres: the fine, its frozen evidence and its outbox event are committed together or
 * not at all, and every event of the service goes through the outbox (nothing is sent without a transaction).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({FineIssuingService.class, FineEvidenceService.class, FineEvents.class, OutboxService.class, JacksonAutoConfiguration.class,
        FineIssuingTransactionTest.Config.class})
class FineIssuingTransactionTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withInitScript("test-schema.sql");

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    @TestConfiguration
    static class Config {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        RuleEngineProperties ruleEngineProperties() {
            return TestFixtures.properties();
        }
    }

    @Autowired
    FineIssuingService issuing;
    @Autowired
    FineEvidenceService evidenceService;
    @Autowired
    ViolationRepository violations;
    @Autowired
    OutboxService outbox;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    PlateOwnerDirectory owners;
    @MockitoBean
    ZoneDirectory zones;
    @MockitoBean
    SpotCacheService spots;
    @MockitoBean
    DetectionDirectory detections;

    private ParkingSpot spot;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate fine_evidence, fines, spot_violations, rule_outbox_events cascade");
        spot = TestFixtures.spot("permit", "resident", 0, 30);
        when(owners.ownerOf(any())).thenReturn(Optional.empty());
        when(zones.addressOf(any())).thenReturn(Optional.of("Storgatan 1"));
        when(spots.spotsOf(TestFixtures.ZONE)).thenReturn(List.of(spot));
        when(detections.involvedIn(any(), any(), any(), any(), anyInt())).thenReturn(List.of(
                new DetectionDirectory.Detection(UUID.randomUUID(), "snapshots/cam-1/a.jpg", new BigDecimal("0.91"), NOW.minusSeconds(120)),
                new DetectionDirectory.Detection(UUID.randomUUID(), "snapshots/cam-1/b.jpg", new BigDecimal("0.87"), NOW.minusSeconds(240))));
    }

    private Violation dueViolation() {
        return violations.saveAndFlush(Violation.open(spot.id(), TestFixtures.ZONE, TestFixtures.PLATE, null, ViolationType.WRONG_PERMIT,
                NOW.minusSeconds(900), NOW.minusSeconds(60), "No valid permit was registered for the plate: the spot requires a resident permit."));
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    @Test
    void theFineItsEvidenceAndItsEventAreCommittedTogether() {
        Violation violation = dueViolation();

        Fine fine = tx.execute(status -> issuing.issueFine(violations.findById(violation.getId()).orElseThrow()));

        assertThat(count("fines")).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("select * from fine_evidence where fine_id = ?", fine.getId());
        assertThat(row).containsEntry("plate", "ABC123").containsEntry("zone_id", "zone-1").containsEntry("zone_address", "Storgatan 1")
                .containsEntry("spot_number", "A1").containsEntry("violation_type", "WRONG_PERMIT").containsEntry("decision_basis", "automatic")
                .containsEntry("grace_minutes", 14);
        assertThat(row.get("rule_summary")).isEqualTo("No valid permit was registered for the plate: the spot requires a resident permit.");
        assertThat(row.get("image_paths").toString()).contains("snapshots/cam-1/a.jpg").contains("snapshots/cam-1/b.jpg");
        assertThat((BigDecimal) row.get("min_confidence")).isEqualByComparingTo("0.87");
        assertThat(jdbc.queryForList("select routing_key from rule_outbox_events where aggregate_id = ?", String.class, fine.getId().toString()))
                .containsExactly("fine.issued");
        assertThat(evidenceService.find(fine.getId())).isPresent();
    }

    @Test
    void aRollbackLeavesNoFineNoEvidenceAndNoEvent() {
        Violation violation = dueViolation();

        tx.executeWithoutResult(status -> {
            issuing.issueFine(violations.findById(violation.getId()).orElseThrow());
            status.setRollbackOnly();
        });

        assertThat(count("fines")).isZero();
        assertThat(count("fine_evidence")).isZero();
        assertThat(count("rule_outbox_events")).isZero();
        assertThat(violations.findById(violation.getId()).orElseThrow().getFineId()).isNull();
    }

    @Test
    void aFailureWhileWritingTheEvidenceRollsBackTheFineAndTheEvent() {
        Violation violation = dueViolation();
        when(detections.involvedIn(any(), any(), any(), any(), anyInt())).thenThrow(new IllegalStateException("detections unreadable"));

        assertThatThrownBy(() -> tx.execute(status -> issuing.issueFine(violations.findById(violation.getId()).orElseThrow())))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count("fines")).isZero();
        assertThat(count("fine_evidence")).isZero();
        assertThat(count("rule_outbox_events")).isZero();
    }

    @Test
    void issuingOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> issuing.issueFine(dueViolation())).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> outbox.enqueue("fine", UUID.randomUUID(), "parkview.violation", "fine.issued", Map.of("a", 1)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count("rule_outbox_events")).isZero();
    }
}
