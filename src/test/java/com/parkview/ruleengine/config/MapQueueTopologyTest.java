package com.parkview.ruleengine.config;

import com.parkview.ruleengine.messaging.DeadLetterConfig;
import com.parkview.ruleengine.messaging.MapEventListener;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code map.updated} must reach every instance: each declares its own exclusive queue instead of competing for one
 * durable queue (one of them would get each event). Not covered without a broker: that two running instances really
 * both receive an event.
 */
class MapQueueTopologyTest {

    private final RabbitConfig config = new RabbitConfig();

    @Test
    void eachInstanceDeclaresItsOwnTemporaryQueue() {
        Queue first = new RabbitConfig().mapUpdatedInstanceQueue();
        Queue second = new RabbitConfig().mapUpdatedInstanceQueue();

        assertThat(first.getName()).startsWith("map.updated.rule-engine.instance-").isNotEqualTo(second.getName());
        assertThat(first.isExclusive()).isTrue();
        assertThat(first.isAutoDelete()).isTrue();
        assertThat(first.isDurable()).isFalse();
        assertThat(first.getArguments()).containsEntry("x-dead-letter-exchange", DeadLetterConfig.DLX);
    }

    @Test
    void theInstanceQueueIsBoundToTheMappingExchangeWithTheEventKey() {
        Queue queue = config.mapUpdatedInstanceQueue();

        Binding binding = config.mapUpdatedBinding(queue, config.mappingExchange());

        assertThat(binding.getDestination()).isEqualTo(queue.getName());
        assertThat(binding.getExchange()).isEqualTo("parkview.mapping");
        assertThat(binding.getRoutingKey()).isEqualTo("map.updated");
    }

    @Test
    void theListenerReadsTheInstanceQueueNotTheSharedOne() throws Exception {
        RabbitListener annotation = MapEventListener.class
                .getMethod("onMapUpdated", com.parkview.ruleengine.dto.event.MapUpdatedEvent.class)
                .getAnnotation(RabbitListener.class);

        assertThat(annotation.queues()).containsExactly("#{mapUpdatedInstanceQueue.name}");
    }

    @Test
    void theSharedQueueIsNoLongerDeclared() {
        assertThat(RabbitConfig.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("mapUpdatedQueue");
    }
}
