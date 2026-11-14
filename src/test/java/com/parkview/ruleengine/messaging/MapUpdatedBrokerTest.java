package com.parkview.ruleengine.messaging;

import com.parkview.ruleengine.service.cache.SpotCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@code map.updated} against a real broker: the event reaches the listener of this instance through its own queue and
 * evicts the zone from the spot cache. Not covered: two running
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
    JdbcTemplate jdbc;
    @Autowired
    SpotCacheService cache;

    @Test
    void anEventEvictsTheZoneFromTheCacheOfThisInstance() throws Exception {
        String zone = "zone-" + UUID.randomUUID();
        jdbc.update("insert into mapping.zones (id, address) values (?, 'Testgatan 1')", zone);
        jdbc.update("""
                insert into mapping.parking_spots (zone_id, type, shape, coordinates, spot_number)
                values (?, 'paid', 'polygon', '[[0, 0], [0, 10], [10, 10], [10, 0]]'::jsonb, 'A1')""", zone);
        assertThat(cache.spotsOf(zone)).extracting("spotNumber").containsExactly("A1");
        jdbc.update("update mapping.parking_spots set spot_number = 'B2' where zone_id = ?", zone);
        assertThat(cache.spotsOf(zone)).extracting("spotNumber").containsExactly("A1");   // still the cached ones

        rabbit.convertAndSend("parkview.mapping", "map.updated", Map.of("zoneId", zone, "versionNumber", 2,
                "spotCount", 1, "changeReason", "test", "timestamp", System.currentTimeMillis()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(cache.spotsOf(zone)).extracting("spotNumber").containsExactly("B2"));
    }
}
