package com.parkview.ruleengine.repository;

import com.parkview.ruleengine.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims the next due rows, one per fine or violation at most: a row is only eligible while no older row of the same
     * aggregate is still unpublished, so the events of one fine leave in the order they were written, and a row that keeps
     * failing holds back only its own aggregate. {@code SKIP LOCKED} lets several instances run the relay at the same time
     * without ever publishing the same row twice; a row claimed by another instance counts as unpublished until that
     * instance commits, so its successors stay untouched meanwhile.
     */
    @Query(value = """
            select e.* from rule_outbox_events e
            where e.published_at is null and e.next_attempt_at <= :now
              and not exists (select 1 from rule_outbox_events older
                              where older.aggregate_id = e.aggregate_id
                                and older.published_at is null
                                and older.seq < e.seq)
            order by e.seq
            limit :batchSize
            for update of e skip locked
            """, nativeQuery = true)
    List<OutboxEvent> lockDueBatch(@Param("now") Instant now, @Param("batchSize") int batchSize);

    long countByPublishedAtIsNull();

    @Query("select min(e.createdAt) from OutboxEvent e where e.publishedAt is null")
    Instant oldestPendingCreatedAt();

    @Modifying
    @Query("delete from OutboxEvent e where e.publishedAt is not null and e.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
