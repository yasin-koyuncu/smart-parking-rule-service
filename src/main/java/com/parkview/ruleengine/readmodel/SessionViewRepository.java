package com.parkview.ruleengine.readmodel;

import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only access to {@link SessionView}; deliberately not a CrudRepository, so there is no save or delete.
 *
 * <p>A session is matched on the site, and on the zone as well when the spot has one. A spot without a zone matches the
 * sessions of the plate anywhere at its site, never one of another site.
 */
@Transactional(readOnly = true)
public interface SessionViewRepository extends Repository<SessionView, UUID> {

    /**
     * The most recently started session of the plate in the zone of the site that has neither ended nor been voided (an
     * operator voided it: it never happened, so it cannot be overstayed).
     */
    Optional<SessionView> findFirstByPlateAndSiteIdAndZoneIdAndEndedAtIsNullAndVoidedAtIsNullOrderByStartedAtDesc(
            String plate, String siteId, String zoneId);

    /** Same, anywhere at the site (a spot without a zone). */
    Optional<SessionView> findFirstByPlateAndSiteIdAndEndedAtIsNullAndVoidedAtIsNullOrderByStartedAtDesc(String plate, String siteId);
}
