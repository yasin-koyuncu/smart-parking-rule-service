package com.parkview.ruleengine.repository;

import com.parkview.ruleengine.domain.Fine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FineRepository extends JpaRepository<Fine, UUID> {
}
