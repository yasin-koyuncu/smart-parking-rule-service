package com.parkview.ruleengine.readmodel;

import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Read-only access to {@link ZoneRuleView}; deliberately not a CrudRepository, so there is no save or delete. */
@Transactional(readOnly = true)
public interface ZoneRuleViewRepository extends Repository<ZoneRuleView, UUID> {

    List<ZoneRuleView> findByZoneIdAndEnabledTrue(String zoneId);
}
