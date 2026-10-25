package com.parkview.ruleengine.repository;

import com.parkview.ruleengine.domain.FineAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface FineActionRepository extends JpaRepository<FineAction, UUID> {

    /** Served by {@code fine_actions_fine_at_idx} (fine_id, at desc) when sorted by {@code at} descending. */
    Page<FineAction> findByFineId(UUID fineId, Pageable pageable);

    /**
     * Whether the fine already has an audit row of this action about this payment ({@code details.paymentId}; a null id
     * matches rows without one). Makes the handling of a redelivered payment event idempotent.
     */
    @Query(value = """
            select exists (select 1 from fine_actions
                           where fine_id = :fineId and action = :action
                             and coalesce(details ->> 'paymentId', '') = coalesce(cast(:paymentId as text), ''))""", nativeQuery = true)
    boolean existsForPayment(UUID fineId, String action, String paymentId);
}
