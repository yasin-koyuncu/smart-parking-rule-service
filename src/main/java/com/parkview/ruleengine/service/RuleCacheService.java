package com.parkview.ruleengine.service;

import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.client.ZoneRuleDirectory;
import com.parkview.ruleengine.config.RuleEngineProperties;
import com.parkview.ruleengine.domain.ZoneInfo;
import com.parkview.ruleengine.domain.ZoneRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Keeps the enabled rules and the master data of a zone in memory for {@code rule-cache-ttl-seconds}: every
 * observation needs them, and edits made by operators become effective within that time. A zone without
 * rules, or one that is not in the database, is cached too (the latter with the default country and timezone).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleCacheService {

    private final ZoneRuleDirectory ruleDirectory;
    private final ZoneDirectory zoneDirectory;
    private final RuleEngineProperties properties;
    private final Clock clock;

    private final Map<String, Entry<List<ZoneRule>>> rules = new ConcurrentHashMap<>();
    private final Map<String, Entry<ZoneInfo>> zones = new ConcurrentHashMap<>();

    public List<ZoneRule> rulesOf(String zoneId) {
        return cached(rules, zoneId, () -> {
            List<ZoneRule> loaded = ruleDirectory.enabledRulesOf(zoneId);
            log.debug("Loaded {} rules for zone {}", loaded.size(), zoneId);
            return loaded;
        });
    }

    public ZoneInfo zoneOf(String zoneId) {
        return cached(zones, zoneId, () -> zoneDirectory.infoOf(zoneId).orElseGet(ZoneInfo::unknown));
    }

    private <T> T cached(Map<String, Entry<T>> cache, String zoneId, Supplier<T> loader) {
        Instant now = clock.instant();
        Entry<T> entry = cache.get(zoneId);
        if (entry == null || entry.expiresAt().isBefore(now)) {
            entry = new Entry<>(loader.get(), now.plus(properties.ruleCacheTtl()));
            cache.put(zoneId, entry);
        }
        return entry.value();
    }

    private record Entry<T>(T value, Instant expiresAt) {
    }
}
