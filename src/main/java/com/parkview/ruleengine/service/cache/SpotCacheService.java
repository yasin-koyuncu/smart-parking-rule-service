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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps the polygons of a site in memory (detections arrive several times per second per camera). The spots belong to the
 * site, in a zone or not (ADR 0009), so the site is the key.
 * Entries expire after {@code spot-cache-ttl-minutes}; the {@code map.updated} event of parking-mapping-service evicts
 * the site at once ({@link #evict}), the TTL is the safety net when that event is missed. A site without spots is cached
 * too, but only for {@code spot-cache-negative-ttl-seconds}: spots drawn after the first observation must not stay invisible
 * for the full TTL.
 *
 * <p>The key is the site and, for spots drawn on a camera view, the view: a site has its legacy spots (no view, raw
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

    /** Counts evictions, so that a load that was running while a site was evicted is not cached (it may carry the old spots). */
    private final AtomicLong evictions = new AtomicLong();

    /** The legacy spots of the site: drawn on no view, raw camera pixels. */
    public List<ParkingSpot> spotsOf(String siteId) {
        return spotsOf(siteId, null);
    }

    /** The spots of a view of the site (pixel space of the view), or the legacy spots of the site when {@code viewId} is null. */
    public List<ParkingSpot> spotsOf(String siteId, UUID viewId) {
        Key key = new Key(siteId, viewId);
        Instant now = clock.instant();
        Entry entry = cache.get(key);
        if (entry == null || entry.expiresAt().isBefore(now)) {
            long evictionsBefore = evictions.get();
            List<ParkingSpot> spots = viewId == null
                    ? spotDirectory.polygonSpotsOf(siteId)
                    : spotDirectory.polygonSpotsOfView(siteId, viewId);
            Duration ttl = spots.isEmpty() ? properties.spotCacheNegativeTtl() : properties.spotCacheTtl();
            entry = new Entry(spots, now.plus(ttl));
            if (evictions.get() == evictionsBefore) {
                cache.put(key, entry);
            }
            log.debug("Loaded {} spots for site {} view {}", spots.size(), siteId, viewId);
        }
        return entry.spots();
    }

    /** Forgets everything cached for the site, the legacy spots and those of every view; the next observation reloads them. */
    public void evict(String siteId) {
        evictions.incrementAndGet();
        cache.keySet().removeIf(key -> Objects.equals(key.siteId(), siteId));
        log.debug("Spot cache of site {} evicted", siteId);
    }

    /** The label of a spot, legacy or on a view. Not cached: it is read once per issued fine. */
    public Optional<String> spotNumberOf(UUID spotId) {
        return spotDirectory.spotNumberOf(spotId);
    }

    private record Key(String siteId, UUID viewId) {
    }

    private record Entry(List<ParkingSpot> spots, Instant expiresAt) {
    }
}
