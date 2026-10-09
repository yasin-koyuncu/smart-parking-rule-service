package com.parkview.ruleengine.service;

import com.parkview.ruleengine.client.SpotDirectory;
import com.parkview.ruleengine.config.RuleEngineProperties;
import com.parkview.ruleengine.domain.ParkingSpot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the polygons of a zone in memory (detections arrive several times per second per camera).
 * Entries expire after {@code spot-cache-ttl-minutes}, which is also how changes made by
 * parking-mapping-service become visible. A zone without spots is cached too.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpotCacheService {

    private final SpotDirectory spotDirectory;
    private final RuleEngineProperties properties;
    private final Clock clock;

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public List<ParkingSpot> spotsOf(String zoneId) {
        Instant now = clock.instant();
        Entry entry = cache.get(zoneId);
        if (entry == null || entry.expiresAt().isBefore(now)) {
            List<ParkingSpot> spots = spotDirectory.polygonSpotsOf(zoneId);
            entry = new Entry(spots, now.plus(properties.spotCacheTtl()));
            cache.put(zoneId, entry);
            log.debug("Loaded {} spots for zone {}", spots.size(), zoneId);
        }
        return entry.spots();
    }

    private record Entry(List<ParkingSpot> spots, Instant expiresAt) {
    }
}
