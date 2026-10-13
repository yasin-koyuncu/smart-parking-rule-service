package com.parkview.ruleengine.repository;

import com.parkview.ruleengine.domain.Fine;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface FineRepository extends JpaRepository<Fine, UUID> {

    Page<Fine> findByPlate(String plate, Pageable pageable);

    Page<Fine> findByPlateAndVoidedAtIsNull(String plate, Pageable pageable);

    Page<Fine> findByZoneId(String zoneId, Pageable pageable);

    Page<Fine> findByZoneIdAndVoidedAtIsNull(String zoneId, Pageable pageable);

    Page<Fine> findByZoneIdAndPaid(String zoneId, boolean paid, Pageable pageable);

    Page<Fine> findByZoneIdAndPaidAndVoidedAtIsNull(String zoneId, boolean paid, Pageable pageable);

    /**
     * Locks the row until the end of the transaction. Void, manual payment and the online payment event all decide on
     * the current state ("unpaid and not voided"), so they must not interleave.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Fine f where f.id = :id")
    Optional<Fine> findByIdForUpdate(UUID id);
}
