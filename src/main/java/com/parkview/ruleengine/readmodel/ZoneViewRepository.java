package com.parkview.ruleengine.readmodel;

import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Read-only access to {@link ZoneView}; deliberately not a CrudRepository, so there is no save or delete. */
@Transactional(readOnly = true)
public interface ZoneViewRepository extends Repository<ZoneView, String> {

    Optional<ZoneView> findById(String id);
}
