package com.parkview.ruleengine.repository;

import com.parkview.ruleengine.domain.PlatePermit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

public interface PlatePermitRepository extends JpaRepository<PlatePermit, UUID>, JpaSpecificationExecutor<PlatePermit> {

    /**
     * How many of the given (lower-case) permit types the plate holds: a permit of the type (case-insensitive) that
     * covers the zone (or all zones) and is valid at {@code at}.
     */
    @Query("""
            select count(distinct lower(p.permitType)) from PlatePermit p
            where p.plate = :plate and lower(p.permitType) in :permitTypes
              and (p.zoneId is null or p.zoneId = :zoneId)
              and p.validFrom <= :at and (p.validTo is null or p.validTo > :at)
            """)
    long countHeldTypes(String plate, Collection<String> permitTypes, String zoneId, Instant at);
}
