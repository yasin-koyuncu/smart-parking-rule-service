package com.parkview.ruleengine.client;

import java.time.Instant;
import java.util.Optional;

/** Port: parking sessions owned by session-service. Today a read of {@code sessions}. */
public interface ActiveSessionDirectory {

    /** Start of the active (not ended, not voided) session of the plate in the zone; empty when there is none. */
    Optional<Instant> startOfActiveSession(String normalizedPlate, String zoneId);
}
