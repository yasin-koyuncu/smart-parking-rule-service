package com.parkview.ruleengine.client.jpa;

import com.parkview.ruleengine.client.PlateOwnerDirectory;
import com.parkview.ruleengine.readmodel.PlateOwnerView;
import com.parkview.ruleengine.readmodel.PlateOwnerViewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class JpaPlateOwnerDirectory implements PlateOwnerDirectory {

    private final PlateOwnerViewRepository plates;

    @Override
    public Optional<UUID> ownerOf(String normalizedPlate) {
        return plates.findFirstByPlate(normalizedPlate).map(PlateOwnerView::getUserId);
    }
}
