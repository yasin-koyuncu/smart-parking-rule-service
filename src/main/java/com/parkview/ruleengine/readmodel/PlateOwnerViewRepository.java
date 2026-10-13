package com.parkview.ruleengine.readmodel;

import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** Read-only access to {@link PlateOwnerView}; deliberately not a CrudRepository, so there is no save or delete. */
@Transactional(readOnly = true)
public interface PlateOwnerViewRepository extends Repository<PlateOwnerView, UUID> {

    Optional<PlateOwnerView> findFirstByPlate(String plate);
}
