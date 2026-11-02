package com.parkview.ruleengine.messaging;

import com.parkview.ruleengine.dto.event.MapUpdatedEvent;
import com.parkview.ruleengine.service.cache.RuleCacheService;
import com.parkview.ruleengine.service.cache.SpotCacheService;
import com.parkview.ruleengine.service.violation.ViolationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code map.updated} from parking-mapping-service and evicts the spots and rules of that site from the caches, so a
 * re-scan, a rollback, "empty the area" or an edited rule or tariff takes effect for the next observation instead of after the
 * cache TTL, and then closes the active violations of spots that no longer exist (there is no foreign key to the spots of
 * parking-mapping-service that would do it, ADR 0010). The event is per site (ADR 0009); one that names only a zone evicts the zone's site.
 * Every instance has its own queue (see {@code RabbitConfig#mapUpdatedInstanceQueue}), so every instance evicts its own cache.
 */
@Component
@RequiredArgsConstructor
public class MapEventListener {

    private final SpotCacheService spotCache;
    private final RuleCacheService ruleCache;
    private final ViolationService violations;

    @RabbitListener(queues = "#{mapUpdatedInstanceQueue.name}")
    public void onMapUpdated(@Payload @Valid MapUpdatedEvent event) {
        String zoneId = blankToNull(event.zoneId());
        String siteId = blankToNull(event.siteId()) != null ? event.siteId().trim() : ruleCache.siteIdOf(zoneId);
        if (siteId != null) {
            spotCache.evict(siteId);
        }
        ruleCache.evict(siteId, zoneId);
        if (siteId != null) {
            violations.resolveOfRemovedSpots(siteId);
        }
    }

    private static String blankToNull(String id) {
        return id == null || id.isBlank() ? null : id.trim();
    }
}
