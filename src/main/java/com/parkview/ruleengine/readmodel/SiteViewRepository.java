package com.parkview.ruleengine.readmodel;

import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Read-only access to {@link SiteView}; deliberately not a CrudRepository, so there is no save or delete. */
@Transactional(readOnly = true)
public interface SiteViewRepository extends Repository<SiteView, String> {

    Optional<SiteView> findById(String id);
}
