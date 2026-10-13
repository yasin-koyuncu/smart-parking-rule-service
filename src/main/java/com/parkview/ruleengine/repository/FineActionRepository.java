package com.parkview.ruleengine.repository;

import com.parkview.ruleengine.domain.FineAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FineActionRepository extends JpaRepository<FineAction, UUID> {

    /** Served by {@code fine_actions_fine_at_idx} (fine_id, at desc) when sorted by {@code at} descending. */
    Page<FineAction> findByFineId(UUID fineId, Pageable pageable);
}
