package com.parkview.ruleengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The service as deployed, against real Postgres and RabbitMQ: events in, rows and events out.
 * Mirrors the cases of the platform end-to-end suite without needing the whole compose stack.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.datasource.password=unused-overridden-by-service-connection",
        "parkview.security.issuer=http://localhost:1/auth/v1",
        "parkview.rule-engine.expire-sweep-ms=300",
        "parkview.rule-engine.stale-sweep-ms=300",
        "parkview.rule-engine.stale-minutes=15"})
class RuleServiceFlowTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withInitScript("test-schema.sql");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    private static final String SPOT_POLYGON = "[[0, 0], [0, 10], [10, 10], [10, 0]]";
    private static final List<List<Integer>> VEHICLE_IN_SPOT = List.of(List.of(0, 0), List.of(0, 10), List.of(10, 10), List.of(10, 0));
    private static final List<List<Integer>> VEHICLE_ELSEWHERE = List.of(List.of(100, 100), List.of(100, 110), List.of(110, 110), List.of(110, 100));
    private static final String PROBE_QUEUE = "probe.violation-events";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RabbitTemplate rabbit;
    @Autowired
    RabbitAdmin admin;
    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        Queue probe = new Queue(PROBE_QUEUE, false, false, true);
        admin.declareQueue(probe);
        admin.declareBinding(BindingBuilder.bind(probe).to(new TopicExchange("parkview.violation", true, false)).with("#"));
        admin.purgeQueue(PROBE_QUEUE, true);
    }

    private String newZone(String spotType, String permitType) {
        String zone = "zone-" + UUID.randomUUID();
        jdbc.update("insert into zones (id, address) values (?, 'Testgatan 1')", zone);
        jdbc.update("""
                insert into parking_spots (zone_id, type, shape, coordinates, spot_number, permit_type)
                values (?, ?, 'polygon', ?::jsonb, 'A1', ?)""", zone, spotType, SPOT_POLYGON, permitType);
        return zone;
    }

    private static String newPlate() {
        return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    private void detect(String zone, String plate, List<List<Integer>> polygon) {
        rabbit.convertAndSend("parkview.vehicle", "vehicle.detected", Map.of(
                "cameraId", "cam-1", "zoneId", zone, "plate", plate, "vehiclePolygon", polygon,
                "iou", 1.0, "confidence", 0.9, "timestamp", System.currentTimeMillis()));
    }

    private Map<String, Object> violationRow(String plate) {
        return jdbc.queryForMap("select * from spot_violations where plate = ?", plate);
    }

    private JsonNode awaitEvent(String routingKey, String plate) {
        JsonNode[] found = new JsonNode[1];
        await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Message message;
            while (found[0] == null && (message = rabbit.receive(PROBE_QUEUE)) != null) {
                JsonNode body = mapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
                if (routingKey.equals(message.getMessageProperties().getReceivedRoutingKey())
                        && plate.equals(body.path("plate").asText())) {
                    assertThat(message.getMessageProperties().getMessageId()).isNotBlank();
                    assertThat((String) message.getMessageProperties().getHeader("x-event-type")).isEqualTo(routingKey);
                    found[0] = body;
                }
            }
            assertThat(found[0]).as("event " + routingKey + " for " + plate).isNotNull();
        });
        return found[0];
    }

    @Test
    void wrongPermitViolationIsCreatedOnceAndPublished() {
        String zone = newZone("permit", "resident");
        String plate = newPlate();
        UUID owner = UUID.randomUUID();
        jdbc.update("insert into user_plates (user_id, plate) values (?, ?)", owner, plate);

        detect(zone, plate, VEHICLE_IN_SPOT);
        detect(zone, plate, VEHICLE_IN_SPOT);
        detect(zone, plate, VEHICLE_IN_SPOT);

        JsonNode created = awaitEvent("violation.created", plate);
        assertThat(created.get("violationType").asText()).isEqualTo("WRONG_PERMIT");
        assertThat(created.get("zoneId").asText()).isEqualTo(zone);
        assertThat(created.get("zoneAddress").asText()).isEqualTo("Testgatan 1");
        assertThat(created.get("userId").asText()).isEqualTo(owner.toString());
        assertThat(created.get("spotNumber").asText()).isEqualTo("A1");
        assertThat(created.get("graceMinutes").asInt()).isEqualTo(30);
        assertThat(created.get("cameraId").asText()).isEqualTo("cam-1");
        assertThat(created.get("timestamp").asLong()).isPositive();

        assertThat(jdbc.queryForObject("select count(*) from spot_violations where plate = ?", Integer.class, plate)).isEqualTo(1);
        assertThat(violationRow(plate).get("violation_type")).isEqualTo("WRONG_PERMIT");
    }

    @Test
    void aValidPermitMeansNoViolationAndAnOpenOneIsResolvedAsCorrected() {
        String zone = newZone("permit", "resident");
        String plate = newPlate();

        detect(zone, plate, VEHICLE_IN_SPOT);
        awaitEvent("violation.created", plate);

        jdbc.update("insert into plate_permits (plate, permit_type, zone_id, created_by) values (?, 'Resident', ?, 'op')", plate, zone);
        detect(zone, plate, VEHICLE_IN_SPOT);

        JsonNode resolved = awaitEvent("violation.resolved", plate);
        assertThat(resolved.get("reason").asText()).isEqualTo("CORRECTED");
        assertThat(resolved.get("occurredAt").asText()).isNotBlank();
        assertThat(violationRow(plate).get("resolution_reason")).isEqualTo("CORRECTED");
        assertThat(violationRow(plate).get("resolved_at")).isNotNull();
    }

    @Test
    void aPlateWithAPermitNeverGetsAViolation() {
        String zone = newZone("permit", "resident");
        String plate = newPlate();
        jdbc.update("insert into plate_permits (plate, permit_type, zone_id, created_by) values (?, 'resident', null, 'op')", plate);

        detect(zone, plate, VEHICLE_IN_SPOT);
        String marker = newPlate();
        detect(newZone("no_parking", null), marker, VEHICLE_IN_SPOT);
        awaitEvent("violation.created", marker);

        assertThat(jdbc.queryForObject("select count(*) from spot_violations where plate = ?", Integer.class, plate)).isZero();
    }

    @Test
    void movingAwayResolvesTheViolationAsLeft() {
        String zone = newZone("no_parking", null);
        String plate = newPlate();

        detect(zone, plate, VEHICLE_IN_SPOT);
        assertThat(awaitEvent("violation.created", plate).get("violationType").asText()).isEqualTo("NO_PARKING");

        detect(zone, plate, VEHICLE_ELSEWHERE);

        assertThat(awaitEvent("violation.resolved", plate).get("reason").asText()).isEqualTo("LEFT");
    }

    @Test
    void expiredViolationBecomesAFineThatIsPaidByThePaymentEvent() {
        String zone = newZone("permit", "resident");
        String plate = newPlate();
        UUID owner = UUID.randomUUID();
        jdbc.update("insert into user_plates (user_id, plate) values (?, ?)", owner, plate);

        detect(zone, plate, VEHICLE_IN_SPOT);
        awaitEvent("violation.created", plate);
        jdbc.update("update spot_violations set grace_until = now() - interval '1 minute' where plate = ?", plate);

        JsonNode fineEvent = awaitEvent("fine.issued", plate);
        UUID fineId = UUID.fromString(fineEvent.get("fineId").asText());
        assertThat(fineEvent.get("amountSek").decimalValue()).isEqualByComparingTo("700");
        assertThat(fineEvent.get("userId").asText()).isEqualTo(owner.toString());
        assertThat(fineEvent.get("zoneAddress").asText()).isEqualTo("Testgatan 1");
        assertThat(fineEvent.get("violationId").asText()).isEqualTo(violationRow(plate).get("id").toString());

        Map<String, Object> fine = jdbc.queryForMap("select * from fines where id = ?", fineId);
        assertThat(fine.get("reason")).isEqualTo("wrong_permit");
        assertThat(fine.get("paid")).isEqualTo(false);
        assertThat(violationRow(plate).get("fine_id")).isEqualTo(fineId);
        assertThat(violationRow(plate).get("fine_issued_at")).isNotNull();

        rabbit.convertAndSend("parkview.payment", "payment.completed.fine", Map.of(
                "paymentId", UUID.randomUUID().toString(), "referenceId", fineId.toString(), "referenceType", "FINE",
                "amountSek", 700, "timestamp", System.currentTimeMillis()));

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select paid from fines where id = ?", Boolean.class, fineId)).isTrue());
    }

    @Test
    void aVehicleThatWasNotSeenForTheStalePeriodIsNotFinedButResolvedAsLeft() {
        String zone = newZone("permit", "resident");
        String plate = newPlate();

        detect(zone, plate, VEHICLE_IN_SPOT);
        awaitEvent("violation.created", plate);
        jdbc.update("""
                update spot_violations set grace_until = now() - interval '1 minute',
                       last_seen_at = now() - interval '20 minutes' where plate = ?""", plate);

        assertThat(awaitEvent("violation.resolved", plate).get("reason").asText()).isEqualTo("LEFT");
        assertThat(jdbc.queryForObject("select count(*) from fines where plate = ?", Integer.class, plate)).isZero();
    }

    @Test
    void invalidVehicleDetectedPayloadsAreDeadLettered() {
        String marker = "bad-" + UUID.randomUUID();
        rabbit.convertAndSend("parkview.vehicle", "vehicle.detected", Map.of("cameraId", marker, "plate", "ABC123"));

        await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            boolean found = false;
            Message message;
            while ((message = rabbit.receive("parkview.dlq")) != null) {
                found |= new String(message.getBody(), StandardCharsets.UTF_8).contains(marker);
            }
            assertThat(found).as("message dead-lettered").isTrue();
        });
    }
}
