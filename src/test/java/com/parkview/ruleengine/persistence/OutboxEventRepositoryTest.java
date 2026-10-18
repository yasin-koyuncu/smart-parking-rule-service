package com.parkview.ruleengine.persistence;

import com.parkview.ruleengine.domain.OutboxEvent;
import com.parkview.ruleengine.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The outbox table and its claim query against real Postgres: order, per-aggregate ordering, back-off, SKIP LOCKED, clean-up. */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxEventRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withInitScript("test-schema.sql");

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");

    @Autowired
    OutboxEventRepository outbox;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void clean() {
        jdbc.update("delete from rule_outbox_events");
    }

    private OutboxEvent enqueue(String aggregate, String routingKey) {
        return outbox.saveAndFlush(OutboxEvent.create("fine", aggregate, "parkview.violation", routingKey, "{\"b\":1,\"a\":2}", "req-1", NOW));
    }

    private List<String> claimKeys(Instant now, int limit) {
        return tx.execute(status -> outbox.lockDueBatch(now, limit).stream().map(OutboxEvent::getRoutingKey).toList());
    }

    @Test
    void aRowRoundTripsWithTheExactPayloadTextAndTheRequestId() {
        OutboxEvent saved = enqueue("f-1", "fine.issued");

        OutboxEvent loaded = outbox.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getPayload()).isEqualTo("{\"b\":1,\"a\":2}");
        assertThat(loaded.getRequestId()).isEqualTo("req-1");
        assertThat(loaded.getAggregateType()).isEqualTo("fine");
        assertThat(loaded.getAggregateId()).isEqualTo("f-1");
        assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(loaded.getPublishedAt()).isNull();
        assertThat(loaded.getAttempts()).isZero();
        assertThat(jdbc.queryForObject("select seq from rule_outbox_events where id = ?", Long.class, saved.getId())).isPositive();
    }

    @Test
    void theTableIsNotThePaymentServicesTable() {
        enqueue("f-1", "fine.issued");

        assertThat(jdbc.queryForObject("select count(*) from rule_outbox_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select to_regclass('public.outbox_events') is null", Boolean.class))
                .as("rule-service must not create or use payment-service's outbox_events").isTrue();
    }

    @Test
    void dueRowsAreClaimedInInsertionOrderUpToTheBatchSize() {
        enqueue("f-1", "fine.issued");
        enqueue("f-2", "fine.issued");
        enqueue("f-3", "fine.issued");
        enqueue("v-1", "violation.created");

        assertThat(claimKeys(NOW, 3)).containsExactly("fine.issued", "fine.issued", "fine.issued");
        assertThat(claimKeys(NOW, 10)).hasSize(4);
    }

    @Test
    void onlyTheOldestUnpublishedRowOfAnAggregateIsOfferedSoNothingOvertakesIt() {
        enqueue("f-1", "fine.issued");
        enqueue("f-1", "fine.disputed");
        enqueue("f-1", "fine.dispute.decided");
        enqueue("f-2", "fine.issued");

        assertThat(claimKeys(NOW, 10)).containsExactly("fine.issued", "fine.issued");

        tx.executeWithoutResult(status -> outbox.lockDueBatch(NOW, 10).stream().filter(e -> e.getAggregateId().equals("f-1"))
                .forEach(e -> e.markPublished(NOW)));
        assertThat(claimKeys(NOW, 10)).containsExactly("fine.disputed", "fine.issued");
    }

    @Test
    void aRowInBackoffHoldsBackItsAggregateButNotOthers() {
        OutboxEvent failing = enqueue("f-1", "fine.issued");
        enqueue("f-1", "fine.voided");
        enqueue("f-2", "fine.issued");
        tx.executeWithoutResult(status -> outbox.findById(failing.getId()).orElseThrow().markFailed("nacked", NOW, Duration.ofSeconds(60)));

        assertThat(claimKeys(NOW, 10)).containsExactly("fine.issued");
        assertThat(claimKeys(NOW.plusSeconds(2), 10)).containsExactly("fine.issued", "fine.issued");
    }

    @Test
    void publishedRowsAreNotClaimedAgainAndFutureRowsWait() {
        OutboxEvent done = enqueue("f-1", "fine.issued");
        tx.executeWithoutResult(status -> outbox.findById(done.getId()).orElseThrow().markPublished(NOW));
        jdbc.update("update rule_outbox_events set next_attempt_at = ?", java.sql.Timestamp.from(NOW.plusSeconds(30)));
        enqueue("f-2", "fine.issued");
        jdbc.update("update rule_outbox_events set next_attempt_at = ? where aggregate_id = 'f-2'", java.sql.Timestamp.from(NOW.plusSeconds(30)));

        assertThat(claimKeys(NOW, 10)).isEmpty();
        assertThat(claimKeys(NOW.plusSeconds(31), 10)).containsExactly("fine.issued");
    }

    @Test
    void twoInstancesNeverClaimTheSameRowAndAClaimedRowKeepsItsSuccessorsBack() throws Exception {
        for (int i = 0; i < 6; i++) {
            enqueue("f-" + i, "fine.issued");
        }
        enqueue("f-0", "fine.voided");
        CountDownLatch firstHoldsLocks = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<UUID>> first = pool.submit(() -> tx.execute(status -> {
                List<UUID> ids = outbox.lockDueBatch(NOW, 3).stream().map(OutboxEvent::getId).toList();
                firstHoldsLocks.countDown();
                await(secondDone);
                return ids;
            }));
            assertThat(firstHoldsLocks.await(10, TimeUnit.SECONDS)).isTrue();
            List<OutboxEvent> second = tx.execute(status -> outbox.lockDueBatch(NOW, 10));
            secondDone.countDown();

            assertThat(second).hasSize(3);
            assertThat(second).extracting(OutboxEvent::getId).doesNotContainAnyElementsOf(first.get(10, TimeUnit.SECONDS));
            assertThat(second).extracting(OutboxEvent::getRoutingKey).doesNotContain("fine.voided");
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void cleanUpRemovesOnlyPublishedRowsOlderThanTheCutoff() {
        OutboxEvent oldPublished = enqueue("f-1", "fine.issued");
        OutboxEvent recentPublished = enqueue("f-2", "fine.issued");
        OutboxEvent pending = enqueue("f-3", "fine.issued");
        jdbc.update("update rule_outbox_events set published_at = ? where id = ?", java.sql.Timestamp.from(NOW.minus(Duration.ofDays(20))), oldPublished.getId());
        jdbc.update("update rule_outbox_events set published_at = ? where id = ?", java.sql.Timestamp.from(NOW.minus(Duration.ofDays(1))), recentPublished.getId());

        int removed = tx.execute(status -> outbox.deletePublishedBefore(NOW.minus(Duration.ofDays(14))));

        assertThat(removed).isEqualTo(1);
        assertThat(outbox.findAll()).extracting(OutboxEvent::getId).containsExactlyInAnyOrder(recentPublished.getId(), pending.getId());
    }

    @Test
    void thePendingCountAndTheOldestPendingTimeFollowTheUnpublishedRows() {
        assertThat(outbox.countByPublishedAtIsNull()).isZero();
        assertThat(outbox.oldestPendingCreatedAt()).isNull();

        OutboxEvent first = outbox.saveAndFlush(OutboxEvent.create("fine", "f-1", "x", "fine.issued", "{}", null, NOW.minusSeconds(600)));
        outbox.saveAndFlush(OutboxEvent.create("fine", "f-2", "x", "fine.issued", "{}", null, NOW));

        assertThat(outbox.countByPublishedAtIsNull()).isEqualTo(2);
        assertThat(outbox.oldestPendingCreatedAt()).isEqualTo(NOW.minusSeconds(600));
        tx.executeWithoutResult(status -> outbox.findById(first.getId()).orElseThrow().markPublished(NOW));
        assertThat(outbox.oldestPendingCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void aRowWithoutPayloadOrRoutingKeyIsRejected() {
        assertThatThrownBy(() -> jdbc.update("insert into rule_outbox_events (aggregate_type, aggregate_id, exchange, routing_key) values ('fine','f','x','k')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
