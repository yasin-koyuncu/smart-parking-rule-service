package com.parkview.ruleengine.client;

import java.util.Optional;
import java.util.UUID;

/** Port: who owns a plate (user registered it in the app). Today a read of {@code user_plates}. */
public interface PlateOwnerLookup {

    Optional<UUID> ownerOf(String normalizedPlate);
}
