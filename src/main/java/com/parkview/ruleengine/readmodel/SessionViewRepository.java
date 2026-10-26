package com.parkview.ruleengine.readmodel;

import org.springframework.data.jpa.repository.Query;
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
     * The most recently started session of the plate in the zone of the site that has not ended. Also returns a voided one:
     * only for a database without {@code sessions.voided_at}, where there is none.
     */
    Optional<SessionView> findFirstByPlateAndSiteIdAndZoneIdAndEndedAtIsNullOrderByStartedAtDesc(String plate, String siteId, String zoneId);

    /** Same, anywhere at the site (a spot without a zone). */
    Optional<SessionView> findFirstByPlateAndSiteIdAndEndedAtIsNullOrderByStartedAtDesc(String plate, String siteId);

    /**
     * The most recently started session of the plate in the zone of the site that has neither ended nor been voided (an
     * operator voided it: it never happened, so it cannot be overstayed). Needs the {@code voided_at} column; native because
     * the column is deliberately not mapped by {@link SessionView}.
     */
    @Query(value = """
            select id, plate, site_id, zone_id, started_at, ended_at from sessions
            where plate = :plate and site_id = :siteId and zone_id = :zoneId and ended_at is null and voided_at is null
            order by started_at desc
            limit 1""", nativeQuery = true)
    Optional<SessionView> findActiveNotVoided(String plate, String siteId, String zoneId);

    /** Same, anywhere at the site (a spot without a zone). */
    @Query(value = """
            select id, plate, site_id, zone_id, started_at, ended_at from sessions
            where plate = :plate and site_id = :siteId and ended_at is null and voided_at is null
            order by started_at desc
            limit 1""", nativeQuery = true)
    Optional<SessionView> findActiveNotVoidedAtSite(String plate, String siteId);
}
