package com.parkview.ruleengine.repository;

import com.parkview.ruleengine.domain.FineEvidence;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/** Evidence is written once and never changed: deliberately only {@code save} and {@code findById}, no update or delete API. */
public interface FineEvidenceRepository extends Repository<FineEvidence, UUID> {

    FineEvidence save(FineEvidence evidence);

    Optional<FineEvidence> findById(UUID fineId);
}
