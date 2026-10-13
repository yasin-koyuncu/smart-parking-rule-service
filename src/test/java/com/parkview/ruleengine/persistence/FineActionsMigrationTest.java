package com.parkview.ruleengine.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Liquibase changeset 008 (void, manual payment, audit table) on the legacy shape of {@code fines} that the Supabase
 * migrations created: existing rows survive, the new columns and constraints appear, and re-running it is harmless.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FineActionsMigrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withInitScript("test-schema.sql");

    private static final String CHANGELOG = "db/changelog/";
    private static final List<String> FINES_CHANGESETS = List.of("003-reconcile-supabase-fines.sql",
            "006-wrong-vehicle-type.sql", "007-fines-read-indexes.sql");
    private static final String THIS_CHANGESET = "008-fine-void-and-manual-payment.sql";
    private static final List<String> NEW_COLUMNS = List.of("voided_at", "void_reason", "voided_by", "paid_at", "paid_method", "paid_by");

    @Autowired
    JdbcTemplate jdbc;

    private void run(String changeset) throws IOException {
        String sql = new String(new ClassPathResource(CHANGELOG + changeset).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        for (String statement : sql.replaceAll("(?m)^--.*$", "").split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement);
            }
        }
    }

    /** The table as the original Supabase migration left it: no camera/reason detail, no user, no image, narrow constraints. */
    private void createLegacyFines() {
        jdbc.execute("drop table if exists fine_actions");
        jdbc.execute("drop table if exists fines cascade");
        jdbc.execute("""
                create table fines (
                  id         uuid primary key default gen_random_uuid(),
                  plate      text not null,
                  zone_id    text not null,
                  camera_id  text not null,
                  reason     text not null check (reason in ('no_parking','overstay','wrong_permit','boundary_exceeded')),
                  amount_sek numeric(8,2) not null,
                  issued_at  timestamptz not null default now(),
                  paid       boolean not null default false,
                  created_at timestamptz not null default now()
                )""");
    }

    private List<String> finesColumns() {
        return jdbc.queryForList("select column_name from information_schema.columns where table_name = 'fines'", String.class);
    }

    private UUID insertLegacyFine(boolean paid) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into fines (id, plate, zone_id, camera_id, reason, amount_sek, paid) values (?, 'ABC123', 'z1', 'cam-1', 'overstay', 450, ?)",
                id, paid);
        return id;
    }

    @Test
    void theLegacyTableKeepsItsRowsAndGainsTheNewColumnsConstraintsTableAndIndex() throws IOException {
        createLegacyFines();
        UUID unpaid = insertLegacyFine(false);
        UUID paid = insertLegacyFine(true);
        assertThat(finesColumns()).doesNotContainAnyElementsOf(NEW_COLUMNS);

        for (String changeset : FINES_CHANGESETS) {
            run(changeset);
        }
        run(THIS_CHANGESET);

        assertThat(finesColumns()).containsAll(NEW_COLUMNS);
        assertThat(jdbc.queryForObject("select count(*) from fines", Integer.class)).isEqualTo(2);
        // rows paid before this change keep paid = true and have no payment detail (the payment event did not record it)
        assertThat(jdbc.queryForObject("select paid from fines where id = ?", Boolean.class, paid)).isTrue();
        assertThat(jdbc.queryForObject("select paid_at from fines where id = ?", Object.class, paid)).isNull();
        assertThat(jdbc.queryForObject("select paid_method from fines where id = ?", Object.class, paid)).isNull();
        assertThat(jdbc.queryForObject("select voided_at from fines where id = ?", Object.class, unpaid)).isNull();

        // the audit table: FK to fines, newest-first index, closed to PostgREST (RLS on, no policies)
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where indexname = 'fine_actions_fine_at_idx'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select relrowsecurity from pg_class where relname = 'fine_actions'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from pg_policies where tablename = 'fine_actions'", Integer.class)).isZero();
        jdbc.update("insert into fine_actions (fine_id, action) values (?, 'void')", unpaid);
        assertThat(jdbc.queryForObject("select details::text from fine_actions", String.class)).isEqualTo("{}");
        assertThatThrownBy(() -> jdbc.update("insert into fine_actions (fine_id, action) values (?, 'void')", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into fine_actions (fine_id, action) values (?, 'refund')", unpaid))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aFineIsNeverBothPaidAndVoidedAndOnlyKnownMethodsAreStored() throws IOException {
        createLegacyFines();
        for (String changeset : FINES_CHANGESETS) {
            run(changeset);
        }
        run(THIS_CHANGESET);
        UUID paid = insertLegacyFine(true);
        UUID unpaid = insertLegacyFine(false);

        assertThatThrownBy(() -> jdbc.update("update fines set voided_at = now() where id = ?", paid))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("update fines set voided_at = now(), void_reason = 'misread plate', voided_by = gen_random_uuid() where id = ?", unpaid);
        assertThatThrownBy(() -> jdbc.update("update fines set paid = true where id = ?", unpaid))
                .isInstanceOf(DataIntegrityViolationException.class);

        for (String method : List.of("online", "cash", "transfer", "other")) {
            jdbc.update("update fines set paid_method = ? where id = ?", method, paid);
        }
        assertThatThrownBy(() -> jdbc.update("update fines set paid_method = 'card' where id = ?", paid))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theChangesetCanBeRunAgainAndKeepsEverything() throws IOException {
        createLegacyFines();
        for (String changeset : FINES_CHANGESETS) {
            run(changeset);
        }
        run(THIS_CHANGESET);
        UUID id = insertLegacyFine(false);
        jdbc.update("update fines set voided_at = now(), void_reason = 'misread plate' where id = ?", id);
        jdbc.update("insert into fine_actions (fine_id, action, reason) values (?, 'void', 'misread plate')", id);

        run(THIS_CHANGESET);
        run(THIS_CHANGESET);

        assertThat(jdbc.queryForObject("select void_reason from fines where id = ?", String.class, id)).isEqualTo("misread plate");
        assertThat(jdbc.queryForObject("select count(*) from fine_actions where fine_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from pg_constraint
                 where conname in ('fines_paid_method_check', 'fines_not_paid_and_voided_check', 'fine_actions_action_check')""",
                Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where indexname = 'fine_actions_fine_at_idx'", Integer.class)).isEqualTo(1);
    }

    @Test
    void theAuditTableAlsoAppearsOnADatabaseWhereTheColumnsAlreadyExist() throws IOException {
        createLegacyFines();
        for (String changeset : FINES_CHANGESETS) {
            run(changeset);
        }
        for (String column : List.of("voided_at timestamptz", "paid_at timestamptz", "void_reason text", "paid_method text")) {
            jdbc.execute("alter table fines add column if not exists " + column);
        }

        run(THIS_CHANGESET);

        assertThat(finesColumns()).containsAll(NEW_COLUMNS);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name = 'fine_actions'", Integer.class)).isEqualTo(1);
    }
}
