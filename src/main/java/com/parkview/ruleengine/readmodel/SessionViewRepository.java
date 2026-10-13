package com.parkview.ruleengine.readmodel;

import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** Read-only access to {@link SessionView}; deliberately not a CrudRepository, so there is no save or delete. */
@Transactional(readOnly = true)
public interface SessionViewRepository extends Repository<SessionView, UUID> {

    /** The most recently started session of the plate in the zone that has not ended. */
    Optional<SessionView> findFirstByPlateAndZoneIdAndEndedAtIsNullOrderByStartedAtDesc(String plate, String zoneId);
}
