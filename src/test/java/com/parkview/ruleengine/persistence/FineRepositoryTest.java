package com.parkview.ruleengine.persistence;

import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.ViolationType;
import com.parkview.ruleengine.repository.FineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The read queries of the fines API against the real Liquibase schema (incl. the columns Fine only reads). */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FineRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withInitScript("test-schema.sql");

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "issuedAt").and(Sort.by("id"));

    @Autowired
    FineRepository fines;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from fines");
    }

    private UUID insert(String plate, String zone, Instant issuedAt, boolean paid) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into fines (id, plate, zone_id, camera_id, reason, reason_code, reason_text, amount_sek, issued_at, paid, user_id)
                values (?, ?, ?, 'cam-1', 'wrong_vehicle_type', 'WVT', 'Not electric', 700, ?, ?, ?)
                """, id, plate, zone, Timestamp.from(issuedAt), paid, UUID.randomUUID());
        return id;
    }

    @Test
    void plateListIsNewestFirstPagedAndNeverContainsOtherPlates() {
        UUID oldest = insert("ABC123", "z1", NOW.minusSeconds(300), false);
        UUID newest = insert("ABC123", "z2", NOW, true);
        UUID middle = insert("ABC123", "z1", NOW.minusSeconds(100), false);
        insert("XYZ999", "z1", NOW.plusSeconds(10), false);

        Page<Fine> first = fines.findByPlate("ABC123", PageRequest.of(0, 2, NEWEST_FIRST));
        Page<Fine> second = fines.findByPlate("ABC123", PageRequest.of(1, 2, NEWEST_FIRST));

        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getContent()).extracting(Fine::getId).containsExactly(newest, middle);
        assertThat(second.getContent()).extracting(Fine::getId).containsExactly(oldest);
    }

    @Test
    void zoneListFiltersOnPaidAndIsNewestFirst() {
        UUID unpaidOld = insert("AAA111", "z1", NOW.minusSeconds(100), false);
        UUID paid = insert("BBB222", "z1", NOW.minusSeconds(50), true);
        UUID unpaidNew = insert("CCC333", "z1", NOW, false);
        insert("DDD444", "z2", NOW, false);

        assertThat(fines.findByZoneId("z1", PageRequest.of(0, 10, NEWEST_FIRST)).getContent())
                .extracting(Fine::getId).containsExactly(unpaidNew, paid, unpaidOld);
        assertThat(fines.findByZoneIdAndPaid("z1", false, PageRequest.of(0, 10, NEWEST_FIRST)).getContent())
                .extracting(Fine::getId).containsExactly(unpaidNew, unpaidOld);
        assertThat(fines.findByZoneIdAndPaid("z1", true, PageRequest.of(0, 10, NEWEST_FIRST)).getContent())
                .extracting(Fine::getId).containsExactly(paid);
    }

    @Test
    void camerasAndReasonDetailAreReadFromTheTable() {
        UUID id = insert("ABC123", "z1", NOW, false);

        Fine fine = fines.findById(id).orElseThrow();

        assertThat(fine.getCameraId()).isEqualTo("cam-1");
        assertThat(fine.getReason()).isEqualTo(ViolationType.WRONG_VEHICLE_TYPE);
        assertThat(fine.getReasonCode()).isEqualTo("WVT");
        assertThat(fine.getReasonText()).isEqualTo("Not electric");
    }
}
