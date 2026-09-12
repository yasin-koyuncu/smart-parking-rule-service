package com.parkview.ruleengine.repository;

import com.parkview.ruleengine.model.entity.Violation;
import com.parkview.ruleengine.model.ViolationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ViolationRepository extends JpaRepository<Violation, UUID> {

    // Aktiva violations för en specifik skylt och typ (undvik dubbletter)
    Optional<Violation> findByPlateAndViolationTypeAndResolvedAtIsNullAndFineIssuedAtIsNull(
            String plate, ViolationType type
    );

    // Alla aktiva violations vars grace period löpt ut (för cron-jobb)
    @Query("""
        SELECT v FROM Violation v
        WHERE v.resolvedAt IS NULL
          AND v.fineIssuedAt IS NULL
          AND v.graceUntil < :now
    """)
    List<Violation> findExpired(Instant now);

    // Aktiva violations per zon (för operatörsvyn)
    @Query("""
        SELECT v FROM Violation v
        WHERE v.zoneId = :zoneId
          AND v.resolvedAt IS NULL
          AND v.fineIssuedAt IS NULL
        ORDER BY v.detectedAt DESC
    """)
    List<Violation> findActiveByZone(String zoneId);

    // Alla violations per skylt
    List<Violation> findByPlateOrderByDetectedAtDesc(String plate);
}