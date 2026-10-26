package com.parkview.ruleengine.repository;

import com.parkview.ruleengine.domain.type.ResolutionReason;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.type.ViolationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * "Active" below always means not resolved and not fined. Statements that race with other
 * writers (detections, the fine sweep, operators) are atomic updates with the state in the
 * {@code where} clause instead of read-modify-write on the entity.
 */
public interface ViolationRepository extends JpaRepository<Violation, UUID> {

    @Query("""
            select v from Violation v
            where v.plate = :plate and v.spotId = :spotId and v.violationType = :type
              and v.resolvedAt is null and v.fineIssuedAt is null
            """)
    Optional<Violation> findActive(String plate, UUID spotId, ViolationType type);

    @Query("""
            select v from Violation v
            where v.plate = :plate and v.zoneId = :zoneId
              and v.resolvedAt is null and v.fineIssuedAt is null
            """)
    List<Violation> findActiveByPlateAndZone(String plate, String zoneId);

    /** The active violations of the plate anywhere at the site, in a zone or not. */
    @Query("""
            select v from Violation v
            where v.plate = :plate and v.siteId = :siteId
              and v.resolvedAt is null and v.fineIssuedAt is null
            """)
    List<Violation> findActiveByPlateAndSite(String plate, String siteId);

    /** The active violations of the plate in the zone of the site. */
    @Query("""
            select v from Violation v
            where v.plate = :plate and v.siteId = :siteId and v.zoneId = :zoneId
              and v.resolvedAt is null and v.fineIssuedAt is null
            """)
    List<Violation> findActiveByPlateAndSiteAndZone(String plate, String siteId, String zoneId);

    /**
     * Ids of fined, unresolved violations of this plate/spot/type that were confirmed recently:
     * the vehicle that was fined is still there, so no second violation must be opened for it.
     */
    @Query("""
            select v.id from Violation v
            where v.plate = :plate and v.spotId = :spotId and v.violationType = :type
              and v.resolvedAt is null and v.fineIssuedAt is not null and v.lastSeenAt >= :seenSince
            order by v.lastSeenAt desc
            """)
    List<UUID> findRecentlyFinedIds(String plate, UUID spotId, ViolationType type, Instant seenSince, Pageable limit);

    @Query("""
            select v from Violation v
            where v.zoneId = :zoneId and v.resolvedAt is null and v.fineIssuedAt is null
            """)
    Page<Violation> findActiveByZone(String zoneId, Pageable pageable);

    /** The active violations of the site, in its zones and outside them. */
    @Query("""
            select v from Violation v
            where v.siteId = :siteId and v.resolvedAt is null and v.fineIssuedAt is null
            """)
    Page<Violation> findActiveBySite(String siteId, Pageable pageable);

    Page<Violation> findByPlate(String plate, Pageable pageable);

    /** The violations of a plate in the given zones only; the collection must not be empty. */
    Page<Violation> findByPlateAndZoneIdIn(String plate, Collection<String> zoneIds, Pageable pageable);

    /** The violations of a plate at the given sites only (an operator's scope); the collection must not be empty. */
    Page<Violation> findByPlateAndSiteIdIn(String plate, Collection<String> siteIds, Pageable pageable);

    /**
     * Active violations whose grace has passed and that were confirmed by a detection since {@code seenSince} (the fine
     * freshness window: the vehicle has to be demonstrably still there).
     */
    @Query("""
            select v.id from Violation v
            where v.resolvedAt is null and v.fineIssuedAt is null
              and v.graceUntil < :now and v.lastSeenAt >= :seenSince
            order by v.graceUntil
            """)
    List<UUID> findDueForFine(Instant now, Instant seenSince, Pageable limit);

    @Query("""
            select v from Violation v
            where v.resolvedAt is null and v.fineIssuedAt is null and v.lastSeenAt < :seenBefore
            order by v.lastSeenAt
            """)
    List<Violation> findStale(Instant seenBefore, Pageable limit);

    /**
     * Locks one due violation for the current transaction. {@code SKIP LOCKED} makes a concurrent
     * instance skip a row that is already being fined instead of waiting for it, and the state
     * predicates are re-checked under the lock, so a row is fined by exactly one instance.
     * Must run inside a transaction.
     */
    @Query(value = """
            select * from spot_violations
            where id = :id and resolved_at is null and fine_issued_at is null
              and grace_until < :now and last_seen_at >= :seenSince
            for update skip locked
            """, nativeQuery = true)
    Optional<Violation> claimDue(UUID id, Instant now, Instant seenSince);

    @Modifying
    @Query("update Violation v set v.lastSeenAt = :at where v.id = :id and v.lastSeenAt < :at")
    int touch(UUID id, Instant at);

    /** @return 1 when this call closed the violation, 0 when it was already resolved, fined or unknown */
    @Modifying
    @Query("""
            update Violation v set v.resolvedAt = :at, v.resolutionReason = :reason
            where v.id = :id and v.resolvedAt is null and v.fineIssuedAt is null
            """)
    int resolveIfActive(UUID id, ResolutionReason reason, Instant at);
}
