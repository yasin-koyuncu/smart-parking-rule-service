package com.parkview.ruleengine.messaging;

import com.parkview.ruleengine.dto.event.MapUpdatedEvent;
import com.parkview.ruleengine.service.cache.SpotCacheService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code map.updated} from parking-mapping-service and evicts the spots of that zone from the cache, so a re-scan,
 * a rollback or "empty the area" takes effect for the next observation instead of after the cache TTL.
 * Every instance has its own queue (see {@code RabbitConfig#mapUpdatedInstanceQueue}), so every instance evicts its own cache.
 */
@Component
@RequiredArgsConstructor
public class MapEventListener {

    private final SpotCacheService spotCache;

    @RabbitListener(queues = "#{mapUpdatedInstanceQueue.name}")
    public void onMapUpdated(@Payload @Valid MapUpdatedEvent event) {
        spotCache.evict(event.zoneId());
    }
}
