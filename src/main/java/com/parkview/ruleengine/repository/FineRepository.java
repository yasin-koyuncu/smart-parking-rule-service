package com.parkview.ruleengine.repository;

import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.type.ViolationType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface FineRepository extends JpaRepository<Fine, UUID> {

    Page<Fine> findByPlate(String plate, Pageable pageable);

    Page<Fine> findByPlateAndVoidedAtIsNull(String plate, Pageable pageable);

    /** The fines of a plate in the given zones only (an operator sees nothing outside their zones); the collection must not be empty. */
    Page<Fine> findByPlateAndZoneIdIn(String plate, Collection<String> zoneIds, Pageable pageable);

    Page<Fine> findByPlateAndZoneIdInAndVoidedAtIsNull(String plate, Collection<String> zoneIds, Pageable pageable);

    Page<Fine> findByZoneId(String zoneId, Pageable pageable);

    Page<Fine> findByZoneIdAndVoidedAtIsNull(String zoneId, Pageable pageable);

    Page<Fine> findByZoneIdAndPaid(String zoneId, boolean paid, Pageable pageable);

    Page<Fine> findByZoneIdAndPaidAndVoidedAtIsNull(String zoneId, boolean paid, Pageable pageable);

    /**
     * Whether the plate was fined for this reason in this zone since {@code since}, voided or not. Independent of the spot:
     * a spot that was deleted and drawn again no longer carries the violation (cascade) and has a new id.
     */
    boolean existsByPlateAndZoneIdAndReasonAndIssuedAtGreaterThanEqual(String plate, String zoneId, ViolationType reason, Instant since);

    /**
     * Locks the row until the end of the transaction. Void, manual payment and the online payment event all decide on
     * the current state ("unpaid and not voided"), so they must not interleave.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Fine f where f.id = :id")
    Optional<Fine> findByIdForUpdate(UUID id);
}
