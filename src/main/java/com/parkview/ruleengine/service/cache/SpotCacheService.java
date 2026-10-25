package com.parkview.ruleengine.service.cache;

import com.parkview.ruleengine.client.SpotDirectory;
import com.parkview.ruleengine.config.RuleEngineProperties;
import com.parkview.ruleengine.domain.value.ParkingSpot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps the polygons of a zone in memory (detections arrive several times per second per camera).
 * Entries expire after {@code spot-cache-ttl-minutes}; the {@code map.updated} event of parking-mapping-service evicts
 * the zone at once ({@link #evict}), the TTL is the safety net when that event is missed. A zone without spots is cached
 * too, but only for {@code spot-cache-negative-ttl-seconds}: spots drawn after the first observation must not stay invisible
 * for the full TTL.
 *
 * <p>The key is the zone and, for spots drawn on a camera view, the view: a zone has its legacy spots (no view, raw
 * camera pixels) and one set of spots per view (pixel space of the view), and an observation is only ever matched
 * against one of these sets, so they are cached and expire separately.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpotCacheService {

    private final SpotDirectory spotDirectory;
    private final RuleEngineProperties properties;
    private final Clock clock;

    private final Map<Key, Entry> cache = new ConcurrentHashMap<>();

    /** Counts evictions, so that a load that was running while a zone was evicted is not cached (it may carry the old spots). */
    private final AtomicLong evictions = new AtomicLong();

    /** The legacy spots of the zone: drawn on no view, raw camera pixels. */
    public List<ParkingSpot> spotsOf(String zoneId) {
        return spotsOf(zoneId, null);
    }

    /** The spots of a view of the zone (pixel space of the view), or the legacy spots of the zone when {@code viewId} is null. */
    public List<ParkingSpot> spotsOf(String zoneId, UUID viewId) {
        Key key = new Key(zoneId, viewId);
        Instant now = clock.instant();
        Entry entry = cache.get(key);
        if (entry == null || entry.expiresAt().isBefore(now)) {
            long evictionsBefore = evictions.get();
            List<ParkingSpot> spots = viewId == null
                    ? spotDirectory.polygonSpotsOf(zoneId)
                    : spotDirectory.polygonSpotsOfView(zoneId, viewId);
            Duration ttl = spots.isEmpty() ? properties.spotCacheNegativeTtl() : properties.spotCacheTtl();
            entry = new Entry(spots, now.plus(ttl));
            if (evictions.get() == evictionsBefore) {
                cache.put(key, entry);
            }
            log.debug("Loaded {} spots for zone {} view {}", spots.size(), zoneId, viewId);
        }
        return entry.spots();
    }

    /** Forgets everything cached for the zone, the legacy spots and those of every view; the next observation reloads them. */
    public void evict(String zoneId) {
        evictions.incrementAndGet();
        cache.keySet().removeIf(key -> key.zoneId().equals(zoneId));
        log.debug("Spot cache of zone {} evicted", zoneId);
    }

    /** The label of a spot, legacy or on a view. Not cached: it is read once per issued fine. */
    public Optional<String> spotNumberOf(UUID spotId) {
        return spotDirectory.spotNumberOf(spotId);
    }

    private record Key(String zoneId, UUID viewId) {
    }

    private record Entry(List<ParkingSpot> spots, Instant expiresAt) {
    }
}
