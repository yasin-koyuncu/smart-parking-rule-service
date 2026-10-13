package com.parkview.ruleengine.persistence;

import org.junit.jupiter.api.BeforeEach;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Liquibase changeset 006 against the real changelog on Postgres 16: the widened CHECK constraints and their idempotency. */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WrongVehicleTypeMigrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withInitScript("test-schema.sql");

    private static final String CHANGESET = "db/changelog/006-wrong-vehicle-type.sql";

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from fines");
        jdbc.update("delete from spot_violations");
    }

    private void insertViolation(String type) {
        jdbc.update("""
                insert into spot_violations (spot_id, zone_id, plate, violation_type, grace_until)
                values (gen_random_uuid(), 'z1', 'ABC123', ?, now())""", type);
    }

    private void insertFine(String reason) {
        jdbc.update("insert into fines (plate, zone_id, reason, amount_sek) values ('ABC123', 'z1', ?, 700)", reason);
    }

    private void runChangeset() throws IOException {
        String sql = new String(new ClassPathResource(CHANGESET).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        for (String statement : sql.replaceAll("(?m)^--.*$", "").split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement);
            }
        }
    }

    @Test
    void theNewTypeIsAcceptedByBothTablesAndStillRestrictedToKnownValues() {
        insertViolation("WRONG_VEHICLE_TYPE");
        insertFine("wrong_vehicle_type");
        for (String type : new String[]{"WRONG_PERMIT", "BOUNDARY_EXCEEDED", "OVERSTAY", "NO_PARKING"}) {
            insertViolation(type);
            insertFine(type.toLowerCase());
        }

        assertThat(jdbc.queryForObject("select count(*) from spot_violations", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from fines", Integer.class)).isEqualTo(5);
        assertThatThrownBy(() -> insertViolation("UNKNOWN_TYPE")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertViolation("wrong_vehicle_type")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertFine("WRONG_VEHICLE_TYPE")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theChangesetCanBeRunAgainAndRepairsNarrowerConstraints() throws IOException {
        runChangeset();
        insertViolation("WRONG_VEHICLE_TYPE");
        jdbc.update("delete from spot_violations");

        // state of a database whose constraints were (re)created by an older changeset or by another service
        jdbc.execute("alter table spot_violations drop constraint spot_violations_violation_type_check");
        jdbc.execute("""
                alter table spot_violations add constraint spot_violations_violation_type_check
                check (violation_type in ('WRONG_PERMIT','BOUNDARY_EXCEEDED','OVERSTAY','NO_PARKING'))""");
        jdbc.execute("alter table fines drop constraint fines_reason_check");
        jdbc.execute("""
                alter table fines add constraint fines_reason_check
                check (reason in ('no_parking','overstay','wrong_permit','boundary_exceeded'))""");
        assertThatThrownBy(() -> insertFine("wrong_vehicle_type")).isInstanceOf(DataIntegrityViolationException.class);

        runChangeset();

        insertViolation("WRONG_VEHICLE_TYPE");
        insertFine("wrong_vehicle_type");
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conname in ('fines_reason_check', 'spot_violations_violation_type_check')", Integer.class))
                .isEqualTo(2);
    }
}
