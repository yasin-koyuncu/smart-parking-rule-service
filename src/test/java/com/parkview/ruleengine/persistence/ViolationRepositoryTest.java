package com.parkview.ruleengine.persistence;

import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.ResolutionReason;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.ViolationType;
import com.parkview.ruleengine.repository.FineRepository;
import com.parkview.ruleengine.repository.ViolationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the real Liquibase changelog against Postgres 16: the DB-level invariants are what is under test. */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ViolationRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");
    private static final Instant SEEN_SINCE = NOW.minus(Duration.ofMinutes(15));

    @Autowired
    ViolationRepository violations;
    @Autowired
    FineRepository fines;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager transactionManager;

    TransactionTemplate tx;
    final UUID spot = UUID.randomUUID();

    @BeforeEach
    void clean() {
        jdbc.update("delete from spot_violations");
        jdbc.update("delete from fines");
        tx = new TransactionTemplate(transactionManager);
    }

    private Violation open(String plate, UUID spotId, ViolationType type, Instant detectedAt, Instant graceUntil) {
        return violations.saveAndFlush(Violation.open(spotId, "z1", plate, null, type, detectedAt, graceUntil));
    }

    private Violation due(String plate) {
        return open(plate, spot, ViolationType.WRONG_PERMIT, NOW.minusSeconds(600), NOW.minusSeconds(60));
    }

    @Test
    void openViolationIsStoredWithUppercaseTypeAndLastSeen() {
        Violation v = open("ABC123", spot, ViolationType.WRONG_PERMIT, NOW, NOW.plusSeconds(600));

        assertThat(jdbc.queryForObject("select violation_type from spot_violations where id = ?", String.class, v.getId()))
                .isEqualTo("WRONG_PERMIT");
        Violation loaded = violations.findById(v.getId()).orElseThrow();
        assertThat(loaded.getLastSeenAt()).isEqualTo(NOW);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.isActive()).isTrue();
    }

    @Test
    void secondOpenViolationForSamePlateSpotAndTypeIsRejectedByTheDatabase() {
        open("ABC123", spot, ViolationType.WRONG_PERMIT, NOW, NOW.plusSeconds(600));

        assertThatThrownBy(() -> open("ABC123", spot, ViolationType.WRONG_PERMIT, NOW, NOW.plusSeconds(600)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theUniqueIndexOnlyCoversOpenViolationsOfTheSameKey() {
        Violation first = open("ABC123", spot, ViolationType.WRONG_PERMIT, NOW, NOW.plusSeconds(600));

        open("ABC123", spot, ViolationType.BOUNDARY_EXCEEDED, NOW, NOW.plusSeconds(600));
        open("ABC123", UUID.randomUUID(), ViolationType.WRONG_PERMIT, NOW, NOW.plusSeconds(600));
        open("XYZ789", spot, ViolationType.WRONG_PERMIT, NOW, NOW.plusSeconds(600));

        tx.executeWithoutResult(s -> violations.resolveIfActive(first.getId(), ResolutionReason.CORRECTED, NOW));
        open("ABC123", spot, ViolationType.WRONG_PERMIT, NOW, NOW.plusSeconds(600));
        assertThat(jdbc.queryForObject("select count(*) from spot_violations where plate = 'ABC123'", Integer.class)).isEqualTo(4);
    }

    @Test
    void aFinedViolationDoesNotBlockANewOneEither() {
        Violation first = open("ABC123", spot, ViolationType.WRONG_PERMIT, NOW, NOW.plusSeconds(600));
        jdbc.update("update spot_violations set fine_issued_at = now() where id = ?", first.getId());

        open("ABC123", spot, ViolationType.WRONG_PERMIT, NOW, NOW.plusSeconds(600));
    }

    @Test
    void resolveIfActiveIsAtomicAndRefusesFinedOrResolvedRows() {
        Violation v = open("ABC123", spot, ViolationType.WRONG_PERMIT, NOW, NOW.plusSeconds(600));

        int first = resolve(v.getId(), ResolutionReason.LEFT);
        int second = resolve(v.getId(), ResolutionReason.MANUAL);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        Violation loaded = violations.findById(v.getId()).orElseThrow();
        assertThat(loaded.getResolutionReason()).isEqualTo(ResolutionReason.LEFT);
        assertThat(loaded.getResolvedAt()).isEqualTo(NOW);

        Violation fined = due("FINED1");
        jdbc.update("update spot_violations set fine_issued_at = now() where id = ?", fined.getId());
        assertThat(resolve(fined.getId(), ResolutionReason.MANUAL)).isZero();
    }

    @Test
    void touchOnlyMovesLastSeenForward() {
        Violation v = open("ABC123", spot, ViolationType.WRONG_PERMIT, NOW, NOW.plusSeconds(600));

        assertThat(touch(v.getId(), NOW.plusSeconds(30))).isEqualTo(1);
        assertThat(touch(v.getId(), NOW.plusSeconds(10))).isZero();

        assertThat(violations.findById(v.getId()).orElseThrow().getLastSeenAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void claimDueReturnsOnlyDueFreshActiveViolations() {
        Violation due = due("DUE111");
        Violation notYetDue = open("LATE11", spot, ViolationType.NO_PARKING, NOW, NOW.plusSeconds(600));
        Violation stale = open("STALE1", spot, ViolationType.NO_PARKING, NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofHours(1)));
        Violation resolved = due("RESOL1");
        jdbc.update("update spot_violations set resolved_at = now() where id = ?", resolved.getId());

        assertThat(claim(due.getId())).isPresent();
        assertThat(claim(notYetDue.getId())).isEmpty();
        assertThat(claim(stale.getId())).isEmpty();
        assertThat(claim(resolved.getId())).isEmpty();
        assertThat(violations.findDueForFine(NOW, SEEN_SINCE, PageRequest.of(0, 10))).containsExactly(due.getId());
    }

    private int resolve(UUID id, ResolutionReason reason) {
        return tx.execute(s -> violations.resolveIfActive(id, reason, NOW));
    }

    private int touch(UUID id, Instant at) {
        return tx.execute(s -> violations.touch(id, at));
    }

    private Optional<Violation> claim(UUID id) {
        return tx.execute(s -> violations.claimDue(id, NOW, SEEN_SINCE));
    }

    @Test
    void aClaimedRowIsSkippedByAConcurrentClaimInsteadOfBlocking() throws Exception {
        Violation v = due("LOCK11");

        tx.executeWithoutResult(first -> {
            assertThat(violations.claimDue(v.getId(), NOW, SEEN_SINCE)).isPresent();

            Optional<Violation> concurrent = CompletableFuture
                    .supplyAsync(() -> new TransactionTemplate(transactionManager)
                            .execute(s -> violations.claimDue(v.getId(), NOW, SEEN_SINCE)))
                    .orTimeout(10, TimeUnit.SECONDS)
                    .join();

            assertThat(concurrent).as("row locked by the first transaction").isEmpty();
        });

        assertThat(claim(v.getId())).as("free again after the first transaction ended").isPresent();
    }

    @Test
    void aViolationFinedByTheFirstClaimerIsNotClaimedAgain() {
        Violation v = due("ONCE11");

        tx.executeWithoutResult(s -> {
            Violation claimed = violations.claimDue(v.getId(), NOW, SEEN_SINCE).orElseThrow();
            Fine fine = fines.save(Fine.issue(claimed, new BigDecimal("700"), null, NOW));
            claimed.markFined(fine.getId(), NOW);
        });

        assertThat(claim(v.getId())).isEmpty();
        Violation loaded = violations.findById(v.getId()).orElseThrow();
        assertThat(loaded.getFineId()).isNotNull();
        assertThat(loaded.getFineIssuedAt()).isEqualTo(NOW);
        assertThat(violations.findActive("ONCE11", spot, ViolationType.WRONG_PERMIT)).isEmpty();
    }

    @Test
    void fineReasonIsStoredLowercaseAndAmountWithTwoDecimals() {
        Violation v = due("FINE11");
        Fine fine = tx.execute(s -> fines.save(Fine.issue(v, new BigDecimal("700"), UUID.randomUUID(), NOW)));

        assertThat(jdbc.queryForObject("select reason from fines where id = ?", String.class, fine.getId()))
                .isEqualTo("wrong_permit");
        assertThat(jdbc.queryForObject("select amount_sek from fines where id = ?", BigDecimal.class, fine.getId()))
                .isEqualByComparingTo("700.00");

        Fine loaded = fines.findById(fine.getId()).orElseThrow();
        assertThat(loaded.getReason()).isEqualTo(ViolationType.WRONG_PERMIT);
        assertThat(loaded.isPaid()).isFalse();
    }

    @Test
    void recentlyFinedLookupIgnoresOldAndResolvedRows() {
        Violation recent = due("FRESH1");
        Violation old = due("OLD111");
        jdbc.update("update spot_violations set fine_issued_at = now() where id in (?, ?)", recent.getId(), old.getId());
        jdbc.update("update spot_violations set last_seen_at = ? where id = ?",
                java.sql.Timestamp.from(NOW.minus(Duration.ofHours(1))), old.getId());

        assertThat(violations.findRecentlyFinedIds("FRESH1", spot, ViolationType.WRONG_PERMIT,
                SEEN_SINCE.minus(Duration.ofDays(3650)), PageRequest.of(0, 1))).containsExactly(recent.getId());
        assertThat(violations.findRecentlyFinedIds("OLD111", spot, ViolationType.WRONG_PERMIT,
                SEEN_SINCE, PageRequest.of(0, 1))).isEmpty();
    }

    @Test
    void zoneAndPlateListsArePagedAndOrdered() {
        open("PAGE01", UUID.randomUUID(), ViolationType.WRONG_PERMIT, NOW.minusSeconds(300), NOW.plusSeconds(600));
        Violation newest = open("PAGE01", UUID.randomUUID(), ViolationType.WRONG_PERMIT, NOW, NOW.plusSeconds(600));
        open("PAGE02", UUID.randomUUID(), ViolationType.WRONG_PERMIT, NOW.minusSeconds(100), NOW.plusSeconds(600));
        Violation resolved = open("PAGE03", UUID.randomUUID(), ViolationType.WRONG_PERMIT, NOW, NOW.plusSeconds(600));
        jdbc.update("update spot_violations set resolved_at = now() where id = ?", resolved.getId());
        Sort newestFirst = Sort.by(Sort.Direction.DESC, "detectedAt");

        var zonePage = violations.findActiveByZone("z1", PageRequest.of(0, 2, newestFirst));
        assertThat(zonePage.getTotalElements()).isEqualTo(3);
        assertThat(zonePage.getContent()).hasSize(2);
        assertThat(zonePage.getContent().get(0).getId()).isEqualTo(newest.getId());

        var platePage = violations.findByPlate("PAGE01", PageRequest.of(0, 10, newestFirst));
        assertThat(platePage.getTotalElements()).isEqualTo(2);
        assertThat(platePage.getContent().get(0).getId()).isEqualTo(newest.getId());
    }

    @Test
    void staleLookupFindsOnlyActiveViolationsNotSeenSince() {
        Violation stale = open("STALE2", spot, ViolationType.WRONG_PERMIT, NOW.minus(Duration.ofHours(1)), NOW);
        open("FRESH2", spot, ViolationType.WRONG_PERMIT, NOW, NOW.plusSeconds(600));

        List<Violation> found = violations.findStale(SEEN_SINCE, PageRequest.of(0, 10));

        assertThat(found).extracting(Violation::getId).containsExactly(stale.getId());
    }

    @Test
    void activeByPlateAndZoneListsOpenViolationsOfThatZoneOnly() {
        Violation mine = open("ZONE01", spot, ViolationType.WRONG_PERMIT, NOW, NOW.plusSeconds(600));
        violations.saveAndFlush(Violation.open(UUID.randomUUID(), "z2", "ZONE01", null, ViolationType.WRONG_PERMIT, NOW, NOW.plusSeconds(600)));

        assertThat(violations.findActiveByPlateAndZone("ZONE01", "z1")).extracting(Violation::getId).containsExactly(mine.getId());
    }
}
