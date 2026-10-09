package com.parkview.ruleengine.repository;

import com.parkview.ruleengine.domain.PlatePermit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.UUID;

public interface PlatePermitRepository extends JpaRepository<PlatePermit, UUID>, JpaSpecificationExecutor<PlatePermit> {

    /** A permit of this type (case-insensitive) that covers the zone (or all zones) and is valid at {@code at}. */
    @Query("""
            select count(p) > 0 from PlatePermit p
            where p.plate = :plate and lower(p.permitType) = lower(:permitType)
              and (p.zoneId is null or p.zoneId = :zoneId)
              and p.validFrom <= :at and (p.validTo is null or p.validTo > :at)
            """)
    boolean existsValid(String plate, String permitType, String zoneId, Instant at);
}
