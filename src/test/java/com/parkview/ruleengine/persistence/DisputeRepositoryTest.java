package com.parkview.ruleengine.persistence;

import com.parkview.ruleengine.domain.Dispute;
import com.parkview.ruleengine.domain.type.DisputeChannel;
import com.parkview.ruleengine.domain.type.DisputeStatus;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.FineAction;
import com.parkview.ruleengine.domain.type.FineActionType;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.type.ViolationType;
import com.parkview.ruleengine.repository.DisputeRepository;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The dispute table against the real Liquibase schema: one open dispute per fine, constraints, latest status, the operator list. */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DisputeRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withInitScript("test-schema.sql");

    private static final Instant T = Instant.parse("2026-10-09T10:00:00Z");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "openedAt").and(Sort.by("id"));

    @Autowired
    DisputeRepository disputes;
    @Autowired
    FineActionRepository actions;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from fines");
    }

    private Fine fine(String zone) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into fines (id, plate, zone_id, reason, amount_sek) values (?, 'ABC123', ?, 'overstay', 450)", id, zone);
        Violation v = Violation.open(UUID.randomUUID(), zone, zone, "ABC123", null, ViolationType.OVERSTAY, T, T);
        Fine fine = Fine.issue(v, new BigDecimal("450"), null, T);
        ReflectionTestUtils.setField(fine, "id", id);
        return fine;
    }

    private Dispute open(Fine fine, Instant at) {
        return disputes.saveAndFlush(Dispute.open(fine, UUID.randomUUID(), DisputeChannel.DRIVER, "I disagree with this fine", at));
    }

    @Test
    void aDisputeRoundTripsWithItsConvertedStatusAndChannel() {
        Fine fine = fine("z1");
        UUID opener = UUID.randomUUID();
        Dispute saved = disputes.saveAndFlush(Dispute.open(fine, opener, DisputeChannel.OPERATOR, "The customer called us", T));

        Dispute loaded = disputes.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getFineId()).isEqualTo(fine.getId());
        assertThat(loaded.getPlate()).isEqualTo("ABC123");
        assertThat(loaded.getZoneId()).isEqualTo("z1");
        assertThat(loaded.getOpenedByUserId()).isEqualTo(opener);
        assertThat(loaded.getChannel()).isEqualTo(DisputeChannel.OPERATOR);
        assertThat(loaded.getMessage()).isEqualTo("The customer called us");
        assertThat(loaded.getStatus()).isEqualTo(DisputeStatus.OPEN);
        assertThat(loaded.getOpenedAt()).isEqualTo(T);
        assertThat(loaded.getDecidedAt()).isNull();
        assertThat(jdbc.queryForMap("select status, channel from fine_disputes where id = ?", saved.getId()))
                .containsEntry("status", "open").containsEntry("channel", "operator");
    }

    @Test
    void closingStoresTheDecision() {
        Dispute dispute = open(fine("z1"), T);
        UUID operator = UUID.randomUUID();

        dispute.close(DisputeStatus.VOIDED, T.plusSeconds(60), operator, "The delivery note proves it");
        disputes.saveAndFlush(dispute);

        assertThat(jdbc.queryForMap("select status, decided_by_user_id, decision_note, decided_at is not null as decided from fine_disputes where id = ?",
                dispute.getId()))
                .containsEntry("status", "voided").containsEntry("decided_by_user_id", operator)
                .containsEntry("decision_note", "The delivery note proves it").containsEntry("decided", true);
    }

    @Test
    void aSecondOpenDisputeForTheSameFineIsRejectedByTheDatabase() {
        Fine fine = fine("z1");
        open(fine, T);

        assertThatThrownBy(() -> open(fine, T.plusSeconds(1))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void afterTheFirstIsClosedANewOneMayBeOpenedAndOtherFinesAreIndependent() {
        Fine fine = fine("z1");
        Dispute first = open(fine, T);
        first.close(DisputeStatus.WITHDRAWN, T.plusSeconds(1), null, null);
        disputes.saveAndFlush(first);

        Dispute second = open(fine, T.plusSeconds(2));
        open(fine("z1"), T);

        assertThat(second.getStatus()).isEqualTo(DisputeStatus.OPEN);
        assertThat(jdbc.queryForObject("select count(*) from fine_disputes where status = 'open'", Integer.class)).isEqualTo(2);
    }

    @Test
    void theStatusChannelAndDecisionColumnsAreConstrained() {
        Fine fine = fine("z1");

        assertThatThrownBy(() -> jdbc.update("insert into fine_disputes (fine_id, plate, zone_id, channel, message, status) "
                + "values (?, 'ABC123', 'z1', 'driver', 'x', 'maybe')", fine.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into fine_disputes (fine_id, plate, zone_id, channel, message) "
                + "values (?, 'ABC123', 'z1', 'robot', 'x')", fine.getId())).isInstanceOf(DataIntegrityViolationException.class);
        // a decided dispute needs its time, an open one must not have it
        assertThatThrownBy(() -> jdbc.update("insert into fine_disputes (fine_id, plate, zone_id, channel, message, status) "
                + "values (?, 'ABC123', 'z1', 'driver', 'x', 'upheld')", fine.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into fine_disputes (fine_id, plate, zone_id, channel, message, status, decided_at) "
                + "values (?, 'ABC123', 'z1', 'driver', 'x', 'open', now())", fine.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> disputes.saveAndFlush(Dispute.open(fine("z1"), null, DisputeChannel.DRIVER, null, T)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aDisputeNeedsAnExistingFineAndGoesWithIt() {
        Fine unknown = fine("z1");
        ReflectionTestUtils.setField(unknown, "id", UUID.randomUUID());
        assertThatThrownBy(() -> open(unknown, T)).isInstanceOf(DataIntegrityViolationException.class);

        Fine fine = fine("z1");
        Dispute dispute = open(fine, T);
        jdbc.update("delete from fines where id = ?", fine.getId());

        assertThat(disputes.findById(dispute.getId())).isEmpty();
    }

    @Test
    void theLatestDisputeOfAFineIsTheNewestOne() {
        Fine fine = fine("z1");
        Dispute old = open(fine, T);
        old.close(DisputeStatus.WITHDRAWN, T.plusSeconds(1), null, null);
        disputes.saveAndFlush(old);
        Dispute newest = open(fine, T.plusSeconds(10));

        assertThat(disputes.findFirstByFineIdOrderByOpenedAtDescIdDesc(fine.getId())).get().extracting(Dispute::getId).isEqualTo(newest.getId());
        assertThat(disputes.findByFineIdAndStatus(fine.getId(), DisputeStatus.OPEN)).get().extracting(Dispute::getId).isEqualTo(newest.getId());
        assertThat(disputes.existsByFineIdAndStatus(fine.getId(), DisputeStatus.WITHDRAWN)).isTrue();
        assertThat(disputes.existsByFineIdAndStatus(fine.getId(), DisputeStatus.UPHELD)).isFalse();
        assertThat(disputes.findFineIdById(newest.getId())).contains(fine.getId());
        assertThat(disputes.findFineIdById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void latestStatusesGiveOneRowPerFineAndSkipFinesWithoutDispute() {
        Fine disputedFine = fine("z1");
        Dispute old = open(disputedFine, T);
        old.close(DisputeStatus.UPHELD, T.plusSeconds(1), UUID.randomUUID(), "stays");
        disputes.saveAndFlush(old);
        Fine reopened = fine("z1");
        Dispute first = open(reopened, T);
        first.close(DisputeStatus.WITHDRAWN, T.plusSeconds(1), null, null);
        disputes.saveAndFlush(first);
        open(reopened, T.plusSeconds(5));
        Fine clean = fine("z1");

        Map<UUID, String> latest = disputes.findLatestStatuses(List.of(disputedFine.getId(), reopened.getId(), clean.getId())).stream()
                .collect(Collectors.toMap(DisputeRepository.LatestStatus::getFineId, DisputeRepository.LatestStatus::getStatus));

        assertThat(latest).containsOnlyKeys(disputedFine.getId(), reopened.getId())
                .containsEntry(disputedFine.getId(), "upheld").containsEntry(reopened.getId(), "open");
    }

    @Test
    void theOperatorListIsFilteredByStatusAndZoneNewestFirstAndPaged() {
        Dispute z1Old = open(fine("z1"), T);
        Dispute z1New = open(fine("z1"), T.plusSeconds(100));
        Dispute z2 = open(fine("z2"), T.plusSeconds(50));
        Dispute z3 = open(fine("z3"), T.plusSeconds(10));
        z1Old.close(DisputeStatus.VOIDED, T.plusSeconds(200), UUID.randomUUID(), "voided by the operator");
        disputes.saveAndFlush(z1Old);
        z3.close(DisputeStatus.WITHDRAWN, T.plusSeconds(200), null, null);
        disputes.saveAndFlush(z3);
        Set<DisputeStatus> open = Set.of(DisputeStatus.OPEN);
        Set<DisputeStatus> decided = Set.of(DisputeStatus.UPHELD, DisputeStatus.VOIDED, DisputeStatus.WITHDRAWN);

        Page<Dispute> allOpen = disputes.findByStatusIn(open, PageRequest.of(0, 10, NEWEST_FIRST));
        Page<Dispute> scoped = disputes.findByStatusInAndZoneIdIn(open, List.of("z1", "z3"), PageRequest.of(0, 10, NEWEST_FIRST));
        Page<Dispute> decidedInZones = disputes.findByStatusInAndZoneIdIn(decided, List.of("z1", "z2"), PageRequest.of(0, 10, NEWEST_FIRST));
        Page<Dispute> firstPage = disputes.findByStatusIn(Set.of(DisputeStatus.values()), PageRequest.of(0, 2, NEWEST_FIRST));
        Page<Dispute> secondPage = disputes.findByStatusIn(Set.of(DisputeStatus.values()), PageRequest.of(1, 2, NEWEST_FIRST));

        assertThat(allOpen.getContent()).extracting(Dispute::getId).containsExactly(z1New.getId(), z2.getId());
        assertThat(scoped.getContent()).extracting(Dispute::getId).containsExactly(z1New.getId());
        assertThat(decidedInZones.getContent()).extracting(Dispute::getId).containsExactly(z1Old.getId());
        assertThat(firstPage.getTotalElements()).isEqualTo(4);
        assertThat(firstPage.getContent()).extracting(Dispute::getId).containsExactly(z1New.getId(), z2.getId());
        assertThat(secondPage.getContent()).extracting(Dispute::getId).containsExactly(z3.getId(), z1Old.getId());
    }

    @Test
    void theAuditTableAcceptsTheDisputeActions() {
        Fine fine = fine("z1");

        for (FineActionType type : List.of(FineActionType.DISPUTE_OPENED, FineActionType.DISPUTE_UPHELD, FineActionType.DISPUTE_VOIDED,
                FineActionType.DISPUTE_WITHDRAWN)) {
            actions.saveAndFlush(FineAction.of(fine.getId(), T, UUID.randomUUID(), type, "reason", Map.of("disputeId", UUID.randomUUID().toString())));
        }

        assertThat(jdbc.queryForList("select action from fine_actions where fine_id = ? order by action", String.class, fine.getId()))
                .containsExactly("dispute_opened", "dispute_upheld", "dispute_voided", "dispute_withdrawn");
    }
}
