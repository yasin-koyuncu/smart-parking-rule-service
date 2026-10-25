package com.parkview.ruleengine.messaging;

import com.parkview.ruleengine.config.RabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Removes the durable queue {@value RabbitConfig#MAP_UPDATED_QUEUE} that earlier releases declared for all instances
 * together. No code declares or consumes it any more, but a queue that is gone from the code is not gone from a broker:
 * it would stay bound to {@code parkview.mapping} and collect every {@code map.updated} for ever. Its content is
 * only cache evictions, so nothing is lost with it.
 *
 * <p>The queue is deleted only when it has no consumer. During a rolling deployment an older instance may still be
 * reading it (and would declare it again if it was deleted under it): the broker refuses, this is logged, and the
 * next start of an instance removes it. A queue that is already gone is not an error.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class LegacyMapQueueCleanup {

    private final AmqpAdmin admin;

    @EventListener(ApplicationReadyEvent.class)
    void removeLegacyQueue() {
        try {
            admin.deleteQueue(RabbitConfig.MAP_UPDATED_QUEUE, true, false);
            log.info("Queue {} of earlier releases is not in use or gone", RabbitConfig.MAP_UPDATED_QUEUE);
        } catch (AmqpException e) {
            log.info("Queue {} of earlier releases is still consumed by an older instance, it is removed at the next start ({})",
                    RabbitConfig.MAP_UPDATED_QUEUE, e.getMessage());
        }
    }
}
