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
     * How many of the given (lower-case) permit types the plate holds in a zone of a site: a permit of the type
     * (case-insensitive) for every site, for the whole site, or for the zone, valid at {@code at}.
     */
    @Query("""
            select count(distinct lower(p.permitType)) from PlatePermit p
            where p.plate = :plate and lower(p.permitType) in :permitTypes
              and (p.siteId is null or p.siteId = :siteId)
              and (p.zoneId is null or p.zoneId = :zoneId)
              and p.validFrom <= :at and (p.validTo is null or p.validTo > :at)
            """)
    long countHeldTypes(String plate, Collection<String> permitTypes, String siteId, String zoneId, Instant at);

    /**
     * Same at a spot without a zone: a permit for every site or for the whole site; a permit for a zone does not cover the
     * parts of the site outside its zones.
     */
    @Query("""
            select count(distinct lower(p.permitType)) from PlatePermit p
            where p.plate = :plate and lower(p.permitType) in :permitTypes
              and (p.siteId is null or p.siteId = :siteId) and p.zoneId is null
              and p.validFrom <= :at and (p.validTo is null or p.validTo > :at)
            """)
    long countHeldTypesAtSite(String plate, Collection<String> permitTypes, String siteId, Instant at);
}
