package com.parkview.ruleengine.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.parkview.platform.security.PlateDirectory;
import com.parkview.platform.security.Plates;
import com.parkview.platform.security.servlet.AccessProperties;
import com.parkview.ruleengine.readmodel.PlateOwnerView;
import com.parkview.ruleengine.readmodel.PlateOwnerViewRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link PlateDirectory} over the published view {@code identity.v_user_plates} ({@link PlateOwnerView}), cached per user for
 * {@code parkview.access.plates-cache-ttl}: no entry outlives it, so a newly registered plate is visible within that time.
 */
@Component
public class ReadModelPlateDirectory implements PlateDirectory {

    private final PlateOwnerViewRepository plates;
    private final Cache<UUID, Set<String>> cache;

    @Autowired
    public ReadModelPlateDirectory(PlateOwnerViewRepository plates, AccessProperties properties) {
        this(plates, properties, Ticker.systemTicker());
    }

    ReadModelPlateDirectory(PlateOwnerViewRepository plates, AccessProperties properties, Ticker ticker) {
        this.plates = plates;
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.platesCacheMax())
                .expireAfterWrite(properties.platesCacheTtl())
                .ticker(ticker)
                .build();
    }

    @Override
    public Set<String> platesOf(String userId) {
        UUID id = parse(userId);
        return id == null ? Set.of() : cache.get(id, this::load);
    }

    private Set<String> load(UUID userId) {
        return plates.findByUserId(userId).stream()
                .map(PlateOwnerView::getPlate)
                .map(Plates::normalize)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static UUID parse(String userId) {
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
