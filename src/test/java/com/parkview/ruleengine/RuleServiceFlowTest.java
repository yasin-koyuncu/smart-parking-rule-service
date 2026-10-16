package com.parkview.ruleengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkview.ruleengine.domain.PaidMethod;
import com.parkview.ruleengine.service.FineManagementService;
import com.parkview.ruleengine.web.ConflictException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @Autowired
    FineManagementService fineService;

    /** Every probe message that {@link #awaitEvent} has read ("routing key body"), so tests can also assert what was NOT repeated. */
    private final List<String> consumed = new ArrayList<>();

    @BeforeEach
    void setUp() {
        consumed.clear();
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

    private void detect(String zone, String plate, String vehicleClass) {
        rabbit.convertAndSend("parkview.vehicle", "vehicle.detected", Map.of(
                "cameraId", "cam-1", "zoneId", zone, "plate", plate, "vehiclePolygon", VEHICLE_IN_SPOT,
                "iou", 1.0, "confidence", 0.9, "timestamp", System.currentTimeMillis(), "vehicleClass", vehicleClass));
    }

    /** Waits until a violation was created for a marker plate: everything sent before it has been processed. */
    private void awaitProcessed() {
        String marker = newPlate();
        detect(newZone("no_parking", null), marker, VEHICLE_IN_SPOT);
        awaitEvent("violation.created", marker);
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
                consumed.add(message.getMessageProperties().getReceivedRoutingKey() + " " + body);
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

        // the stream is told once, with the online method; the payment is recorded in the audit trail without an actor
        JsonNode paidEvent = awaitEvent("fine.paid", plate);
        assertThat(paidEvent.get("fineId").asText()).isEqualTo(fineId.toString());
        assertThat(paidEvent.get("zoneId").asText()).isEqualTo(zone);
        assertThat(paidEvent.get("amountSek").decimalValue()).isEqualByComparingTo("700");
        assertThat(paidEvent.get("method").asText()).isEqualTo("online");
        assertThat(paidEvent.get("occurredAt").asText()).isNotBlank();
        assertThat(paidEvent.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "fineId", "plate", "zoneId", "amountSek", "method", "occurredAt");
        Map<String, Object> paidFine = jdbc.queryForMap("select * from fines where id = ?", fineId);
        assertThat(paidFine.get("paid_method")).isEqualTo("online");
        assertThat(paidFine.get("paid_at")).isNotNull();
        assertThat(paidFine.get("paid_by")).isNull();
        Map<String, Object> audit = jdbc.queryForMap("select * from fine_actions where fine_id = ?", fineId);
        assertThat(audit.get("action")).isEqualTo("paid_online");
        assertThat(audit.get("actor_id")).isNull();
        assertThat(audit.get("details").toString()).contains("online");
    }

    private UUID insertFine(String plate, String zone) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into fines (id, plate, zone_id, reason, amount_sek) values (?, ?, ?, 'overstay', 450)", id, plate, zone);
        return id;
    }

    private String plateOf(UUID fineId) {
        return jdbc.queryForObject("select plate from fines where id = ?", String.class, fineId);
    }

    private void payOnline(UUID fineId) {
        rabbit.convertAndSend("parkview.payment", "payment.completed.fine", Map.of(
                "paymentId", UUID.randomUUID().toString(), "referenceId", fineId.toString(), "referenceType", "FINE",
                "amountSek", 450, "timestamp", System.currentTimeMillis()));
    }

    private long published(String routingKey, String plate) {
        return consumed.stream().filter(m -> m.startsWith(routingKey + " ") && m.contains("\"" + plate + "\"")).count();
    }

    @Test
    void aRepeatedPaymentEventDoesNotPublishFinePaidASecondTime() {
        UUID fineId = insertFine(newPlate(), newZone("paid", null));
        UUID otherFine = insertFine(newPlate(), newZone("paid", null));

        payOnline(fineId);
        awaitEvent("fine.paid", plateOf(fineId));
        payOnline(fineId);
        payOnline(otherFine);
        // the payment queue is processed in order: once the other fine is announced the repeat has been handled
        awaitEvent("fine.paid", plateOf(otherFine));

        assertThat(published("fine.paid", plateOf(fineId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from fine_actions where fine_id = ?", Integer.class, fineId)).isEqualTo(1);
    }

    @Test
    void anOperatorMarkingAFineAsPaidWritesTheRowsAndPublishesFinePaidAfterCommit() {
        String zone = newZone("paid", null);
        UUID fineId = insertFine(newPlate(), zone);
        UUID operator = UUID.randomUUID();

        fineService.markPaidManually(fineId, "Paid at the counter", PaidMethod.CASH, operator);
        fineService.markPaidManually(fineId, "Paid again", PaidMethod.TRANSFER, operator);
        // a late online payment of the same fine changes nothing either; its marker follows on the same queue
        UUID marker = insertFine(newPlate(), newZone("paid", null));
        payOnline(fineId);
        payOnline(marker);

        JsonNode event = awaitEvent("fine.paid", plateOf(fineId));
        awaitEvent("fine.paid", plateOf(marker));
        assertThat(event.get("method").asText()).isEqualTo("cash");
        assertThat(event.get("zoneId").asText()).isEqualTo(zone);
        assertThat(event.get("amountSek").decimalValue()).isEqualByComparingTo("450");
        assertThat(published("fine.paid", plateOf(fineId))).isEqualTo(1);
        Map<String, Object> fine = jdbc.queryForMap("select * from fines where id = ?", fineId);
        assertThat(fine.get("paid")).isEqualTo(true);
        assertThat(fine.get("paid_method")).isEqualTo("cash");
        assertThat(fine.get("paid_by")).isEqualTo(operator);
        assertThat(fine.get("paid_at")).isNotNull();
        Map<String, Object> audit = jdbc.queryForMap("select * from fine_actions where fine_id = ?", fineId);
        assertThat(audit.get("action")).isEqualTo("mark_paid");
        assertThat(audit.get("actor_id")).isEqualTo(operator);
        assertThat(audit.get("reason")).isEqualTo("Paid at the counter");
        assertThat(audit.get("details").toString()).contains("cash");
    }

    @Test
    void voidingAFineWritesTheRowsPublishesFineVoidedAndBlocksLaterPayments() {
        UUID fineId = insertFine(newPlate(), newZone("paid", null));
        UUID operator = UUID.randomUUID();

        fineService.voidFine(fineId, "Plate misread", operator);
        fineService.voidFine(fineId, "Plate misread", operator);
        assertThatThrownBy(() -> fineService.markPaidManually(fineId, "Paid", PaidMethod.CASH, operator))
                .isInstanceOf(ConflictException.class);
        // a payment that raced with the void changes nothing; the marker's event proves it has been handled
        UUID marker = insertFine(newPlate(), newZone("paid", null));
        payOnline(fineId);
        payOnline(marker);

        JsonNode event = awaitEvent("fine.voided", plateOf(fineId));
        awaitEvent("fine.paid", plateOf(marker));
        assertThat(event.get("fineId").asText()).isEqualTo(fineId.toString());
        assertThat(event.fieldNames()).toIterable().containsExactlyInAnyOrder("fineId", "plate", "zoneId", "occurredAt");
        assertThat(published("fine.voided", plateOf(fineId))).isEqualTo(1);
        assertThat(published("fine.paid", plateOf(fineId))).isZero();
        Map<String, Object> fine = jdbc.queryForMap("select * from fines where id = ?", fineId);
        assertThat(fine.get("voided_at")).isNotNull();
        assertThat(fine.get("void_reason")).isEqualTo("Plate misread");
        assertThat(fine.get("voided_by")).isEqualTo(operator);
        assertThat(fine.get("paid")).isEqualTo(false);
        assertThat(jdbc.queryForObject("select count(*) from fine_actions where fine_id = ?", Integer.class, fineId)).isEqualTo(1);
    }

    @Test
    void aVoidAndAPaymentAtTheSameTimeLeaveExactlyOneOutcome() throws Exception {
        UUID fineId = insertFine(newPlate(), newZone("paid", null));
        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            boolean voidIt = i % 2 == 0;
            results.add(pool.submit(() -> {
                start.await();
                try {
                    if (voidIt) {
                        fineService.voidFine(fineId, "Plate misread", UUID.randomUUID());
                    } else {
                        fineService.markPaidManually(fineId, "Paid at the counter", PaidMethod.CASH, UUID.randomUUID());
                    }
                    return true;
                } catch (ConflictException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> result : results) {
            succeeded += result.get(30, TimeUnit.SECONDS) ? 1 : 0;
        }
        pool.shutdown();

        Map<String, Object> fine = jdbc.queryForMap("select paid, voided_at from fines where id = ?", fineId);
        boolean paid = (boolean) fine.get("paid");
        boolean voided = fine.get("voided_at") != null;
        assertThat(paid ^ voided).as("exactly one of paid / voided").isTrue();
        assertThat(succeeded).as("every command of the winning kind succeeds (idempotent), every other one conflicts").isEqualTo(attempts / 2);
        assertThat(jdbc.queryForObject("select count(*) from fine_actions where fine_id = ?", Integer.class, fineId)).isEqualTo(1);
        assertThat(awaitEvent(paid ? "fine.paid" : "fine.voided", plateOf(fineId)).get("fineId").asText()).isEqualTo(fineId.toString());
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
    void aZoneRuleForTrucksOnlyHitsTrucksAndUsesTheDetectorClass() {
        String zone = newZone("paid", null);
        jdbc.update("""
                insert into zone_rules (zone_id, label, priority, vehicle_types, no_parking)
                values (?, 'No trucks', 10, '{truck}', true)""", zone);
        String car = newPlate();
        String truck = newPlate();

        detect(zone, car, "car");
        detect(zone, truck, "truck");

        JsonNode created = awaitEvent("violation.created", truck);
        assertThat(created.get("violationType").asText()).isEqualTo("NO_PARKING");
        assertThat(created.get("graceMinutes").asInt()).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from spot_violations where plate = ?", Integer.class, car)).isZero();
    }

    @Test
    void aStayLongerThanTheZoneLimitBecomesAnOverstayAndALaterTypeChangeResolvesIt() {
        String zone = newZone("paid", null);
        jdbc.update("update zones set max_minutes = 60 where id = ?", zone);
        String plate = newPlate();
        jdbc.update("insert into sessions (plate, zone_id, started_at) values (?, ?, now() - interval '90 minutes')", plate, zone);

        detect(zone, plate, VEHICLE_IN_SPOT);

        JsonNode created = awaitEvent("violation.created", plate);
        assertThat(created.get("violationType").asText()).isEqualTo("OVERSTAY");
        assertThat(created.get("graceMinutes").asInt()).isEqualTo(5);
        assertThat(violationRow(plate).get("violation_type")).isEqualTo("OVERSTAY");

        jdbc.update("update sessions set ended_at = now() where plate = ?", plate);
        detect(zone, plate, VEHICLE_IN_SPOT);

        assertThat(awaitEvent("violation.resolved", plate).get("reason").asText()).isEqualTo("CORRECTED");
    }

    @Test
    void anEvSpotCannotBeJudgedWhenTheRegistryIsUnreachable() {
        String zone = newZone("ev", null);
        String plate = newPlate();

        detect(zone, plate, VEHICLE_IN_SPOT);
        awaitProcessed();

        assertThat(jdbc.queryForObject("select count(*) from spot_violations where plate = ?", Integer.class, plate)).isZero();
    }

    @Test
    void theFineEventCarriesTheViolationTypeAndAWrongVehicleTypeFineIsAccepted() {
        String zone = newZone("no_parking", null);
        String plate = newPlate();

        detect(zone, plate, VEHICLE_IN_SPOT);
        awaitEvent("violation.created", plate);
        jdbc.update("update spot_violations set grace_until = now() - interval '1 minute' where plate = ?", plate);

        JsonNode fine = awaitEvent("fine.issued", plate);

        assertThat(fine.get("violationType").asText()).isEqualTo("NO_PARKING");
        jdbc.update("insert into fines (plate, zone_id, reason, amount_sek) values (?, ?, 'wrong_vehicle_type', 700)", plate, zone);
        jdbc.update("""
                insert into spot_violations (spot_id, zone_id, plate, violation_type, grace_until)
                values (gen_random_uuid(), ?, ?, 'WRONG_VEHICLE_TYPE', now())""", zone, plate);
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
