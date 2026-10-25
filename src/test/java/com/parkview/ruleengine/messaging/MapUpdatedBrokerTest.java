package com.parkview.ruleengine.messaging;

import com.parkview.ruleengine.config.RabbitConfig;
import com.parkview.ruleengine.service.cache.SpotCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code map.updated} against a real broker: the event reaches the listener of this instance through its own queue and
 * evicts the zone from the spot cache, and the shared queue of earlier releases is removed. Not covered: two running
 * instances (the topology test shows that each declares its own queue).
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.datasource.password=unused-overridden-by-service-connection",
        "parkview.security.issuer=http://localhost:1/auth/v1"})
class MapUpdatedBrokerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withInitScript("test-schema.sql");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    RabbitTemplate rabbit;
    @Autowired
    RabbitAdmin admin;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SpotCacheService cache;
    @Autowired
    LegacyMapQueueCleanup cleanup;

    @Test
    void anEventEvictsTheZoneFromTheCacheOfThisInstance() throws Exception {
        String zone = "zone-" + UUID.randomUUID();
        jdbc.update("insert into zones (id, address) values (?, 'Testgatan 1')", zone);
        jdbc.update("""
                insert into parking_spots (zone_id, type, shape, coordinates, spot_number)
                values (?, 'paid', 'polygon', '[[0, 0], [0, 10], [10, 10], [10, 0]]'::jsonb, 'A1')""", zone);
        assertThat(cache.spotsOf(zone)).extracting("spotNumber").containsExactly("A1");
        jdbc.update("update parking_spots set spot_number = 'B2' where zone_id = ?", zone);
        assertThat(cache.spotsOf(zone)).extracting("spotNumber").containsExactly("A1");   // still the cached ones

        rabbit.convertAndSend("parkview.mapping", "map.updated", Map.of("zoneId", zone, "versionNumber", 2,
                "spotCount", 1, "changeReason", "test", "timestamp", System.currentTimeMillis()));

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && "A1".equals(cache.spotsOf(zone).get(0).spotNumber())) {
            Thread.sleep(100);
        }
        assertThat(cache.spotsOf(zone)).extracting("spotNumber").containsExactly("B2");
    }

    @Test
    void theSharedQueueOfEarlierReleasesIsRemovedFromTheBroker() {
        Queue legacy = DeadLetterConfig.durableQueue(RabbitConfig.MAP_UPDATED_QUEUE);
        admin.declareQueue(legacy);
        admin.declareBinding(BindingBuilder.bind(legacy).to(new TopicExchange("parkview.mapping", true, false)).with("map.updated"));
        assertThat(admin.getQueueProperties(RabbitConfig.MAP_UPDATED_QUEUE)).isNotNull();

        cleanup.removeLegacyQueue();

        assertThat(admin.getQueueProperties(RabbitConfig.MAP_UPDATED_QUEUE)).isNull();
    }
}
