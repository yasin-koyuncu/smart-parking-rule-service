package com.parkview.ruleengine.readmodel;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only access to {@link DetectionView}; deliberately not a CrudRepository, so there is no save or delete. */
@Transactional(readOnly = true)
public interface DetectionViewRepository extends Repository<DetectionView, UUID> {

    /**
     * Detections of the plate (as read, or as corrected by a reviewer) at the site within the window, newest first; rejected
     * ones are left out. The site and not the zone: a spot without a zone is watched by cameras that may have no zone either,
     * and a camera of a zone may watch spots of the site outside it.
     */
    @Query("""
            select d from DetectionView d
            where d.siteId = :siteId and (d.plate = :plate or d.correctedPlate = :plate)
              and d.at >= :from and d.at <= :to and d.reviewStatus <> 'rejected'
            order by d.at desc
            """)
    List<DetectionView> findInvolved(String plate, String siteId, Instant from, Instant to, Pageable limit);
}
