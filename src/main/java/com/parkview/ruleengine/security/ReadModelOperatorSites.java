package com.parkview.ruleengine.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.parkview.platform.security.OperatorSites;
import com.parkview.platform.security.servlet.AccessProperties;
import com.parkview.ruleengine.readmodel.UserSiteView;
import com.parkview.ruleengine.readmodel.UserSiteViewRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link OperatorSites} over the published view {@code identity.v_user_sites} ({@link UserSiteView}), cached per user for
 * {@code parkview.access.sites-cache-ttl}: no entry outlives it, so a site an administrator adds or removes applies within that
 * time. Database errors propagate: a request is then refused, never allowed.
 */
@Component
public class ReadModelOperatorSites implements OperatorSites {

    private final UserSiteViewRepository sites;
    private final Cache<UUID, Set<String>> cache;

    @Autowired
    public ReadModelOperatorSites(UserSiteViewRepository sites, AccessProperties properties) {
        this(sites, properties, Ticker.systemTicker());
    }

    ReadModelOperatorSites(UserSiteViewRepository sites, AccessProperties properties, Ticker ticker) {
        this.sites = sites;
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.sitesCacheMax())
                .expireAfterWrite(properties.sitesCacheTtl())
                .ticker(ticker)
                .build();
    }

    @Override
    public Set<String> sitesOf(String userId) {
        UUID id = parse(userId);
        return id == null ? Set.of() : cache.get(id, this::load);
    }

    private Set<String> load(UUID userId) {
        return sites.findByUserId(userId).stream()
                .map(UserSiteView::getSiteId)
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
