package com.parkview.ruleengine.persistence;

import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.type.PaidMethod;
import com.parkview.ruleengine.domain.type.ViolationType;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
    @Autowired
    PlatformTransactionManager transactionManager;

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

    // ---- voided fines ----

    private void markVoided(UUID id) {
        jdbc.update("update fines set voided_at = ?, void_reason = 'misread plate', voided_by = ? where id = ?",
                Timestamp.from(NOW), UUID.randomUUID(), id);
    }

    @Test
    void voidedFinesAreLeftOutOfTheListsUnlessAskedFor() {
        UUID visible = insert("ABC123", "z1", NOW, false);
        UUID voided = insert("ABC123", "z1", NOW.minusSeconds(10), false);
        UUID paid = insert("ABC123", "z1", NOW.minusSeconds(20), true);
        markVoided(voided);
        PageRequest page = PageRequest.of(0, 10, NEWEST_FIRST);

        assertThat(fines.findByPlate("ABC123", page).getContent()).extracting(Fine::getId).containsExactly(visible, voided, paid);
        assertThat(fines.findByPlateAndVoidedAtIsNull("ABC123", page).getContent()).extracting(Fine::getId).containsExactly(visible, paid);
        assertThat(fines.findByZoneId("z1", page).getContent()).extracting(Fine::getId).containsExactly(visible, voided, paid);
        assertThat(fines.findByZoneIdAndVoidedAtIsNull("z1", page).getContent()).extracting(Fine::getId).containsExactly(visible, paid);
        assertThat(fines.findByZoneIdAndPaid("z1", false, page).getContent()).extracting(Fine::getId).containsExactly(visible, voided);
        assertThat(fines.findByZoneIdAndPaidAndVoidedAtIsNull("z1", false, page).getContent()).extracting(Fine::getId).containsExactly(visible);
        assertThat(fines.findByZoneIdAndPaidAndVoidedAtIsNull("z1", true, page).getContent()).extracting(Fine::getId).containsExactly(paid);
        assertThat(fines.findByPlateAndVoidedAtIsNull("ABC123", page).getTotalElements()).isEqualTo(2);
    }

    @Test
    void voidingAndMarkingAsPaidArePersistedWithTheirDetail() {
        UUID voidedId = insert("ABC123", "z1", NOW, false);
        UUID paidId = insert("DEF456", "z1", NOW, false);
        UUID operator = UUID.randomUUID();

        Fine toVoid = fines.findById(voidedId).orElseThrow();
        toVoid.voidFine(NOW.plusSeconds(5), "misread plate", operator);
        fines.save(toVoid);
        Fine toPay = fines.findById(paidId).orElseThrow();
        toPay.markPaid(NOW.plusSeconds(7), PaidMethod.TRANSFER, operator);
        fines.save(toPay);

        Fine voided = fines.findById(voidedId).orElseThrow();
        assertThat(voided.isVoided()).isTrue();
        assertThat(voided.getVoidedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(voided.getVoidReason()).isEqualTo("misread plate");
        assertThat(voided.getVoidedBy()).isEqualTo(operator);
        assertThat(voided.isPaid()).isFalse();
        Fine paid = fines.findById(paidId).orElseThrow();
        assertThat(paid.isPaid()).isTrue();
        assertThat(paid.getPaidAt()).isEqualTo(NOW.plusSeconds(7));
        assertThat(paid.getPaidMethod()).isEqualTo("transfer");
        assertThat(paid.getPaidBy()).isEqualTo(operator);
        assertThat(paid.isVoided()).isFalse();
    }

    @Test
    void theRowLockQueryFindsTheFineInsideATransactionAndNothingForAnUnknownId() {
        UUID id = insert("ABC123", "z1", NOW, false);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Fine locked = tx.execute(status -> fines.findByIdForUpdate(id).orElseThrow());
        boolean unknownFound = Boolean.TRUE.equals(tx.execute(status -> fines.findByIdForUpdate(UUID.randomUUID()).isPresent()));

        assertThat(locked.getId()).isEqualTo(id);
        assertThat(unknownFound).isFalse();
    }


    @Test
    void plateListInZonesLeavesOutOtherZonesAndVoidedFinesOnRequest() {
        UUID inZ1 = insert("ABC123", "z1", NOW, false);
        insert("ABC123", "z2", NOW.minusSeconds(10), false);
        UUID voidedInZ1 = insert("ABC123", "z1", NOW.minusSeconds(20), false);
        jdbc.update("update fines set voided_at = now() where id = ?", voidedInZ1);

        assertThat(fines.findByPlateAndZoneIdInAndVoidedAtIsNull("ABC123", java.util.List.of("z1"), PageRequest.of(0, 10, NEWEST_FIRST))
                .getContent()).extracting(Fine::getId).containsExactly(inZ1);
        assertThat(fines.findByPlateAndZoneIdIn("ABC123", java.util.List.of("z1"), PageRequest.of(0, 10, NEWEST_FIRST)).getContent())
                .extracting(Fine::getId).containsExactly(inZ1, voidedInZ1);
        assertThat(fines.findByPlateAndZoneIdIn("ABC123", java.util.List.of("z3"), PageRequest.of(0, 10, NEWEST_FIRST)).getContent()).isEmpty();
    }

    @Test
    void aRecentFineOfTheSamePlateZoneAndReasonIsFoundWhateverTheSpot() {
        insert("ABC123", "z1", NOW.minusSeconds(3600), false);   // reason wrong_vehicle_type
        Instant since = NOW.minusSeconds(6 * 3600);

        assertThat(fines.existsByPlateAndZoneIdAndReasonAndIssuedAtGreaterThanEqual("ABC123", "z1", ViolationType.WRONG_VEHICLE_TYPE, since)).isTrue();
        // another reason, plate or zone, or a fine older than the window, does not count
        assertThat(fines.existsByPlateAndZoneIdAndReasonAndIssuedAtGreaterThanEqual("ABC123", "z1", ViolationType.OVERSTAY, since)).isFalse();
        assertThat(fines.existsByPlateAndZoneIdAndReasonAndIssuedAtGreaterThanEqual("XYZ999", "z1", ViolationType.WRONG_VEHICLE_TYPE, since)).isFalse();
        assertThat(fines.existsByPlateAndZoneIdAndReasonAndIssuedAtGreaterThanEqual("ABC123", "z2", ViolationType.WRONG_VEHICLE_TYPE, since)).isFalse();
        assertThat(fines.existsByPlateAndZoneIdAndReasonAndIssuedAtGreaterThanEqual("ABC123", "z1", ViolationType.WRONG_VEHICLE_TYPE,
                NOW.minusSeconds(60))).isFalse();
    }
}
