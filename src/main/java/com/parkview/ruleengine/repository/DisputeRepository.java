package com.parkview.ruleengine.repository;

import com.parkview.ruleengine.domain.Dispute;
import com.parkview.ruleengine.domain.type.DisputeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisputeRepository extends JpaRepository<Dispute, UUID> {

    /** The latest dispute of the fine (served by {@code fine_disputes_fine_opened_idx}). */
    Optional<Dispute> findFirstByFineIdOrderByOpenedAtDescIdDesc(UUID fineId);

    Optional<Dispute> findByFineIdAndStatus(UUID fineId, DisputeStatus status);

    boolean existsByFineIdAndStatus(UUID fineId, DisputeStatus status);

    /** The fine of a dispute, read without loading the dispute so that the commands can lock the fine row before they read it. */
    @Query("select d.fineId from Dispute d where d.id = :id")
    Optional<UUID> findFineIdById(UUID id);

    /** All sites (administrators); the caller sorts by {@code openedAt} descending. */
    Page<Dispute> findByStatusIn(Collection<DisputeStatus> statuses, Pageable pageable);

    /** The given zones only (served by {@code fine_disputes_zone_opened_idx}). */
    Page<Dispute> findByStatusInAndZoneIdIn(Collection<DisputeStatus> statuses, Collection<String> zoneIds, Pageable pageable);

    /** The given sites only, in their zones and outside them (served by {@code fine_disputes_site_opened_idx}). */
    Page<Dispute> findByStatusInAndSiteIdIn(Collection<DisputeStatus> statuses, Collection<String> siteIds, Pageable pageable);

    /** The status of the latest dispute of each fine, for the fines that have one. */
    @Query(value = """
            select distinct on (fine_id) fine_id as fineId, status as status
            from fine_disputes
            where fine_id in (:fineIds)
            order by fine_id, opened_at desc, id desc
            """, nativeQuery = true)
    List<LatestStatus> findLatestStatuses(Collection<UUID> fineIds);

    interface LatestStatus {

        UUID getFineId();

        String getStatus();
    }
}
