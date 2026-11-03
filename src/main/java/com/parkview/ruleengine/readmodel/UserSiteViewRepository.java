package com.parkview.ruleengine.readmodel;

import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Read-only access to {@link UserSiteView}; deliberately not a CrudRepository, so there is no save or delete. */
@Transactional(readOnly = true)
public interface UserSiteViewRepository extends Repository<UserSiteView, UserSiteView.Key> {

    /** Every site a user may operate. */
    List<UserSiteView> findByUserId(UUID userId);
}
