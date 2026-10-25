package com.parkview.ruleengine.persistence;

import com.parkview.ruleengine.domain.FineAction;
import com.parkview.ruleengine.domain.type.FineActionType;
import com.parkview.ruleengine.repository.FineActionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The audit trail table through JPA against the real Liquibase schema: jsonb details, ordering, constraints. */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FineActionRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withInitScript("test-schema.sql");

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "at").and(Sort.by("id"));

    @Autowired
    FineActionRepository actions;
    @Autowired
    JdbcTemplate jdbc;

    private UUID fineId;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from fines");
        fineId = UUID.randomUUID();
        jdbc.update("insert into fines (id, plate, zone_id, reason, amount_sek) values (?, 'ABC123', 'z1', 'overstay', 450)", fineId);
    }

    @Test
    void anEntryRoundTripsWithItsJsonDetailsAndNullableColumns() {
        UUID operator = UUID.randomUUID();
        FineAction saved = actions.save(FineAction.of(fineId, NOW, operator, FineActionType.MARK_PAID, "Paid at the counter",
                Map.of("method", "cash")));
        FineAction online = actions.save(FineAction.of(fineId, NOW.plusSeconds(1), null, FineActionType.PAID_ONLINE, null,
                Map.of("method", "online", "paymentId", "pay-1")));

        FineAction loaded = actions.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getFineId()).isEqualTo(fineId);
        assertThat(loaded.getAt()).isEqualTo(NOW);
        assertThat(loaded.getActorId()).isEqualTo(operator);
        assertThat(loaded.getAction()).isEqualTo("mark_paid");
        assertThat(loaded.getReason()).isEqualTo("Paid at the counter");
        assertThat(loaded.getDetails()).isEqualTo(Map.of("method", "cash"));
        FineAction loadedOnline = actions.findById(online.getId()).orElseThrow();
        assertThat(loadedOnline.getActorId()).isNull();
        assertThat(loadedOnline.getReason()).isNull();
        assertThat(loadedOnline.getDetails()).containsEntry("paymentId", "pay-1");
        assertThat(jdbc.queryForObject("select details ->> 'method' from fine_actions where id = ?", String.class, saved.getId()))
                .isEqualTo("cash");
    }

    @Test
    void historyIsNewestFirstPagedAndNeverContainsOtherFines() {
        UUID other = UUID.randomUUID();
        jdbc.update("insert into fines (id, plate, zone_id, reason, amount_sek) values (?, 'XYZ999', 'z1', 'overstay', 450)", other);
        FineAction oldest = actions.save(FineAction.of(fineId, NOW.minusSeconds(100), null, FineActionType.PAID_ONLINE, null, Map.of()));
        FineAction newest = actions.save(FineAction.of(fineId, NOW, UUID.randomUUID(), FineActionType.VOID, "misread", Map.of()));
        FineAction middle = actions.save(FineAction.of(fineId, NOW.minusSeconds(50), UUID.randomUUID(), FineActionType.MARK_PAID, "cash", Map.of()));
        actions.save(FineAction.of(other, NOW.plusSeconds(10), null, FineActionType.PAID_ONLINE, null, Map.of()));

        Page<FineAction> first = actions.findByFineId(fineId, PageRequest.of(0, 2, NEWEST_FIRST));
        Page<FineAction> second = actions.findByFineId(fineId, PageRequest.of(1, 2, NEWEST_FIRST));

        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getContent()).extracting(FineAction::getId).containsExactly(newest.getId(), middle.getId());
        assertThat(second.getContent()).extracting(FineAction::getId).containsExactly(oldest.getId());
    }

    @Test
    void anUnknownActionOrFineIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> jdbc.update("insert into fine_actions (fine_id, action) values (?, 'refund')", fineId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> actions.saveAndFlush(FineAction.of(UUID.randomUUID(), NOW, null, FineActionType.VOID, "x", Map.of())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingAFineDeletesItsTrail() {
        actions.save(FineAction.of(fineId, NOW, null, FineActionType.PAID_ONLINE, null, Map.of()));

        jdbc.update("delete from fines where id = ?", fineId);

        assertThat(actions.findByFineId(fineId, PageRequest.of(0, 10)).getTotalElements()).isZero();
    }


    @Test
    void thePaymentReviewActionIsAcceptedByTheConstraintAndFoundPerPayment() {
        actions.save(FineAction.of(fineId, NOW, null, FineActionType.PAYMENT_NEEDS_REVIEW, "Payment completed but needs review: voided",
                Map.of("paymentId", "pay-1", "causes", java.util.List.of("voided"), "fineAmountSek", "450.00")));
        actions.save(FineAction.of(fineId, NOW, null, FineActionType.PAID_ONLINE, null, Map.of("method", "online")));

        assertThat(actions.existsForPayment(fineId, "payment_needs_review", "pay-1")).isTrue();
        assertThat(actions.existsForPayment(fineId, "payment_needs_review", "pay-2")).isFalse();
        assertThat(actions.existsForPayment(fineId, "paid_online", "pay-1")).isFalse();
        // a payment without an id matches rows without one only
        assertThat(actions.existsForPayment(fineId, "paid_online", null)).isTrue();
        assertThat(actions.existsForPayment(fineId, "payment_needs_review", null)).isFalse();
    }
}
