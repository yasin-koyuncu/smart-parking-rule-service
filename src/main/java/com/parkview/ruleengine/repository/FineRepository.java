package com.parkview.ruleengine.repository;

import com.parkview.ruleengine.domain.Fine;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FineRepository extends JpaRepository<Fine, UUID> {

    Page<Fine> findByPlate(String plate, Pageable pageable);

    Page<Fine> findByZoneId(String zoneId, Pageable pageable);

    Page<Fine> findByZoneIdAndPaid(String zoneId, boolean paid, Pageable pageable);
}
