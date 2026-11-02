package com.parkview.ruleengine.persistence;

import com.parkview.ruleengine.config.RetentionProperties;
import com.parkview.ruleengine.service.retention.RuleRetention;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The retention statements against the real changelog: what is anonymised or deleted, what stays, idempotence, and the guard trigger. */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(RuleRetentionTest.Config.class)
class RuleRetentionTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withInitScript("test-schema.sql");

    @TestConfiguration
    static class Config {
        @Bean
        RetentionProperties retentionProperties() {
            return new RetentionProperties(true, false, "0 25 3 * * *", 2, 10, Period.ofDays(30), Period.ofDays(30), Period.ofDays(30));
        }

        @Bean
        RuleRetention ruleRetention(NamedParameterJdbcTemplate jdbc, RetentionProperties properties, PlatformTransactionManager tm) {
            return new RuleRetention(jdbc, properties, tm);
        }
    }

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final Instant CUTOFF = NOW.minus(30, ChronoUnit.DAYS);
    private static final Instant OLD = NOW.minus(90, ChronoUnit.DAYS);
    private static final Instant RECENT = NOW.minus(1, ChronoUnit.DAYS);

    @Autowired
    RuleRetention retention;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("truncate fine_evidence, fines cascade");
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private UUID paidFine(Instant paidAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into fines (id, plate, site_id, zone_id, reason, amount_sek, issued_at, paid, paid_at, paid_method, user_id, image_path) "
                + "values (?, 'ABC123', 'z1', 'z1', 'overstay', 450, ?, true, ?, 'cash', gen_random_uuid(), 'snapshots/a.jpg')", id, ts(paidAt), ts(paidAt));
        return id;
    }

    private UUID voidedFine(Instant voidedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into fines (id, plate, site_id, zone_id, reason, amount_sek, issued_at, voided_at, void_reason, voided_by, user_id) "
                + "values (?, 'ABC123', 'z1', 'z1', 'overstay', 450, ?, ?, 'free text', gen_random_uuid(), gen_random_uuid())", id, ts(voidedAt), ts(voidedAt));
        return id;
    }

    private void evidence(UUID fineId, Instant issuedAt) {
        jdbc.update("insert into fine_evidence (fine_id, plate, site_id, zone_id, violation_type, violation_id, rule_summary, grace_minutes, "
                + "violation_detected_at, grace_expired_at, issued_at, detection_ids, image_paths) "
                + "values (?, 'ABC123', 'z1', 'z1', 'OVERSTAY', gen_random_uuid(), 'rule text', 5, ?, ?, ?, '[\"d1\"]'::jsonb, '[\"a.jpg\"]'::jsonb)",
                fineId, ts(issuedAt), ts(issuedAt), ts(issuedAt));
    }

    private Map<String, Object> row(String table, String idColumn, UUID id) {
        return jdbc.queryForMap("select * from " + table + " where " + idColumn + " = ?", id);
    }

    @Test
    void evidenceOfOldFinesLosesPlateAndReferencesAndNothingElse() {
        UUID oldFine = paidFine(OLD);
        UUID recentFine = paidFine(RECENT);
        evidence(oldFine, OLD);
        evidence(recentFine, RECENT);

        RuleRetention.Counts counts = retention.run(CUTOFF, CUTOFF, CUTOFF);

        assertThat(counts.evidence()).isEqualTo(1);
        Map<String, Object> old = row("fine_evidence", "fine_id", oldFine);
        assertThat(old.get("plate")).isEqualTo(RuleRetention.ANONYMISED);
        assertThat(old.get("image_paths").toString()).isEqualTo("[]");
        assertThat(old.get("detection_ids").toString()).isEqualTo("[]");
        assertThat(old.get("rule_summary")).isEqualTo("rule text");
        Map<String, Object> recent = row("fine_evidence", "fine_id", recentFine);
        assertThat(recent.get("plate")).isEqualTo("ABC123");
        assertThat(recent.get("image_paths").toString()).contains("a.jpg");
    }

    @Test
    void evidenceOfAFineWithAnOpenDisputeIsKept() {
        UUID fine = paidFine(OLD);
        evidence(fine, OLD);
        jdbc.update("insert into fine_disputes (fine_id, plate, site_id, zone_id, channel, message) values (?, 'ABC123', 'z1', 'z1', 'driver', 'please')", fine);

        assertThat(retention.run(CUTOFF, CUTOFF, CUTOFF).evidence()).isZero();
        assertThat(row("fine_evidence", "fine_id", fine).get("plate")).isEqualTo("ABC123");
    }

    @Test
    void closedRecordsLoseTheirPersonalDataAndFreeTextAndKeepTheRest() {
        UUID paid = paidFine(OLD);
        UUID voided = voidedFine(OLD);
        UUID recentPaid = paidFine(RECENT);
        jdbc.update("insert into fine_actions (fine_id, action, reason) values (?, 'void', 'operator text')", voided);
        jdbc.update("insert into fine_actions (fine_id, action, reason) values (?, 'void', 'operator text')", recentPaid);
        jdbc.update("insert into fine_disputes (fine_id, plate, site_id, zone_id, opened_by_user_id, channel, message, status, decided_by_user_id, "
                + "decided_at, decision_note) values (?, 'ABC123', 'z1', 'z1', gen_random_uuid(), 'driver', 'my message', 'upheld', gen_random_uuid(), ?, 'note')",
                paid, ts(OLD));

        RuleRetention.Counts counts = retention.run(CUTOFF, CUTOFF, CUTOFF);

        assertThat(counts.fines()).isEqualTo(2);
        assertThat(counts.disputes()).isEqualTo(1);
        assertThat(counts.actions()).isEqualTo(1);
        Map<String, Object> fine = row("fines", "id", voided);
        assertThat(fine.get("plate")).isEqualTo(RuleRetention.ANONYMISED);
        assertThat(fine.get("user_id")).isNull();
        assertThat(fine.get("void_reason")).isNull();
        assertThat(fine.get("voided_at")).isNotNull();
        assertThat(fine.get("voided_by")).isNotNull();
        assertThat(row("fines", "id", paid).get("image_path")).isNull();
        assertThat(row("fines", "id", recentPaid).get("plate")).isEqualTo("ABC123");
        Map<String, Object> dispute = row("fine_disputes", "fine_id", paid);
        assertThat(dispute.get("plate")).isEqualTo(RuleRetention.ANONYMISED);
        assertThat(dispute.get("message")).isEqualTo("");
        assertThat(dispute.get("decision_note")).isNull();
        assertThat(dispute.get("opened_by_user_id")).isNull();
        assertThat(row("fine_actions", "fine_id", voided).get("reason")).isNull();
        assertThat(row("fine_actions", "fine_id", recentPaid).get("reason")).isEqualTo("operator text");
    }

    @Test
    void onlyResolvedViolationsWithoutAFineAreDeleted() {
        jdbc.update("insert into spot_violations (spot_id, site_id, plate, violation_type, grace_until, resolved_at) "
                + "values (gen_random_uuid(), 'z1', 'OLD111', 'OVERSTAY', now(), ?)", ts(OLD));
        jdbc.update("insert into spot_violations (spot_id, site_id, plate, violation_type, grace_until, resolved_at) "
                + "values (gen_random_uuid(), 'z1', 'NEW111', 'OVERSTAY', now(), ?)", ts(RECENT));
        jdbc.update("insert into spot_violations (spot_id, site_id, plate, violation_type, grace_until) "
                + "values (gen_random_uuid(), 'z1', 'OPEN11', 'OVERSTAY', now())");
        UUID fine = paidFine(OLD);
        jdbc.update("insert into spot_violations (spot_id, site_id, plate, violation_type, grace_until, resolved_at, fine_id, fine_issued_at) "
                + "values (gen_random_uuid(), 'z1', 'FINED1', 'OVERSTAY', now(), ?, ?, ?)", ts(OLD), fine, ts(OLD));

        assertThat(retention.count(CUTOFF, CUTOFF, CUTOFF).violations()).isEqualTo(1);
        assertThat(retention.run(CUTOFF, CUTOFF, CUTOFF).violations()).isEqualTo(1);

        assertThat(jdbc.queryForList("select plate from spot_violations order by plate", String.class))
                .containsExactly("FINED1", "NEW111", "OPEN11");
    }

    @Test
    void aSecondRunFindsNothingAndSmallBatchesStillDrainTheBacklog() {
        for (int i = 0; i < 5; i++) {
            UUID fine = paidFine(OLD);
            evidence(fine, OLD);
        }

        RuleRetention.Counts first = retention.run(CUTOFF, CUTOFF, CUTOFF);
        RuleRetention.Counts second = retention.run(CUTOFF, CUTOFF, CUTOFF);

        assertThat(first.evidence()).isEqualTo(5);
        assertThat(first.fines()).isEqualTo(5);
        assertThat(second.evidence()).isZero();
        assertThat(second.fines()).isZero();
        assertThat(retention.count(CUTOFF, CUTOFF, CUTOFF)).isEqualTo(new RuleRetention.Counts(0, 0, 0, 0, 0, false));
    }
}
