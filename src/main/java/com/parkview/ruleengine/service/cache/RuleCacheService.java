package com.parkview.ruleengine.service.cache;

import com.parkview.ruleengine.client.SiteDirectory;
import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.client.ZoneRuleDirectory;
import com.parkview.ruleengine.config.RuleEngineProperties;
import com.parkview.ruleengine.domain.value.ZoneInfo;
import com.parkview.ruleengine.domain.value.ZoneRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Keeps the enabled rules and the master data of zones and sites in memory for {@code rule-cache-ttl-seconds}: every
 * observation needs them, and edits made by operators become effective within that time, or at once when
 * {@code map.updated} evicts the site ({@link #evict}). A zone or site without rules, or one that is not in the database,
 * is cached too (the latter with the default country and timezone).
 *
 * <p>The effective rules of a spot (ADR 0009): in a zone the zone's parameters, its zone rules and the site rules of its
 * site; without a zone the site's parameters and its site rules. Which rule wins is decided by the resolver.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleCacheService {

    private final ZoneRuleDirectory ruleDirectory;
    private final ZoneDirectory zoneDirectory;
    private final SiteDirectory siteDirectory;
    private final RuleEngineProperties properties;
    private final Clock clock;

    private final Map<String, Entry<List<ZoneRule>>> zoneRules = new ConcurrentHashMap<>();
    private final Map<String, Entry<List<ZoneRule>>> siteRules = new ConcurrentHashMap<>();
    private final Map<String, Entry<ZoneInfo>> zones = new ConcurrentHashMap<>();
    private final Map<String, Entry<ZoneInfo>> sites = new ConcurrentHashMap<>();
    private final Map<String, Entry<Optional<String>>> siteOfZone = new ConcurrentHashMap<>();

    /**
     * The rules that apply at a spot: the zone rules of {@code zoneId} (none when it is null) and the site rules of
     * {@code siteId}, in no particular order.
     */
    public List<ZoneRule> rulesOf(String siteId, String zoneId) {
        remember(siteId, zoneId);
        List<ZoneRule> ofZone = zoneId == null ? List.of() : cached(zoneRules, zoneId, () -> {
            List<ZoneRule> loaded = ruleDirectory.enabledRulesOf(zoneId);
            log.debug("Loaded {} rules for zone {}", loaded.size(), zoneId);
            return loaded;
        });
        List<ZoneRule> ofSite = siteId == null ? List.of() : cached(siteRules, siteId, () -> {
            List<ZoneRule> loaded = ruleDirectory.enabledSiteRulesOf(siteId);
            log.debug("Loaded {} site rules for site {}", loaded.size(), siteId);
            return loaded;
        });
        if (ofSite.isEmpty()) {
            return ofZone;
        }
        if (ofZone.isEmpty()) {
            return ofSite;
        }
        List<ZoneRule> all = new ArrayList<>(ofZone.size() + ofSite.size());
        all.addAll(ofZone);
        all.addAll(ofSite);
        return List.copyOf(all);
    }

    /** The parameters that apply at a spot: the zone's when it has one, otherwise the site's. */
    public ZoneInfo placeOf(String siteId, String zoneId) {
        return zoneId != null ? zoneOf(zoneId) : siteOf(siteId);
    }

    public ZoneInfo zoneOf(String zoneId) {
        return cached(zones, zoneId, () -> zoneDirectory.infoOf(zoneId).orElseGet(ZoneInfo::unknown));
    }

    public ZoneInfo siteOf(String siteId) {
        if (siteId == null) {
            return ZoneInfo.unknownSite();
        }
        return cached(sites, siteId, () -> siteDirectory.infoOf(siteId).orElseGet(ZoneInfo::unknownSite));
    }

    /**
     * The site of a zone, for a message that names only the zone (a producer from before ADR 0009). A zone that is not in the
     * database is taken to be its own site, which is true of every zone that existed before sites did. Null for a null zone.
     */
    public String siteIdOf(String zoneId) {
        if (zoneId == null) {
            return null;
        }
        return cached(siteOfZone, zoneId, () -> zoneDirectory.siteOf(zoneId)).orElse(zoneId);
    }

    /**
     * Forgets the site rules and master data of the site, and everything cached for its zones (and for {@code zoneId}, when
     * set): the next observation reloads them. Called for {@code map.updated}, which a change of a site rule, a zone rule or
     * a tariff publishes too.
     */
    public void evict(String siteId, String zoneId) {
        if (siteId != null) {
            siteRules.remove(siteId);
            sites.remove(siteId);
            siteOfZone.forEach((zone, entry) -> {
                if (Objects.equals(entry.value().orElse(zone), siteId)) {
                    zoneRules.remove(zone);
                    zones.remove(zone);
                }
            });
        }
        if (zoneId != null) {
            zoneRules.remove(zoneId);
            zones.remove(zoneId);
            siteOfZone.remove(zoneId);
        }
        log.debug("Rule cache of site {} zone {} evicted", siteId, zoneId);
    }

    /** Notes which site a zone belongs to, so that evicting the site also evicts what is cached for the zone. */
    private void remember(String siteId, String zoneId) {
        if (siteId != null && zoneId != null) {
            siteOfZone.putIfAbsent(zoneId, new Entry<>(Optional.of(siteId), clock.instant().plus(properties.ruleCacheTtl())));
        }
    }

    private <T> T cached(Map<String, Entry<T>> cache, String key, Supplier<T> loader) {
        Instant now = clock.instant();
        Entry<T> entry = cache.get(key);
        if (entry == null || entry.expiresAt().isBefore(now)) {
            entry = new Entry<>(loader.get(), now.plus(properties.ruleCacheTtl()));
            cache.put(key, entry);
        }
        return entry.value();
    }

    private record Entry<T>(T value, Instant expiresAt) {
    }
}
