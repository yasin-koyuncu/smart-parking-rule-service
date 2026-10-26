package com.parkview.ruleengine.messaging;

import com.parkview.ruleengine.service.violation.ViolationService;
import com.parkview.ruleengine.service.cache.RuleCacheService;
import com.parkview.ruleengine.service.cache.SpotCacheService;
import com.parkview.ruleengine.dto.event.MapUpdatedEvent;
import com.parkview.ruleengine.dto.event.SessionEndedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkview.ruleengine.dto.event.FineIssuedEvent;
import com.parkview.ruleengine.dto.event.FinePaidEvent;
import com.parkview.ruleengine.dto.event.FineVoidedEvent;
import com.parkview.ruleengine.dto.event.PaymentCompletedEvent;
import com.parkview.ruleengine.dto.event.VehicleDetectedEvent;
import com.parkview.ruleengine.dto.event.ViolationCreatedEvent;
import com.parkview.ruleengine.dto.event.ViolationResolvedEvent;
import com.parkview.ruleengine.domain.type.ResolutionReason;
import com.parkview.ruleengine.domain.type.ViolationType;
import com.parkview.ruleengine.service.violation.DetectionService;
import com.parkview.ruleengine.service.fine.FineSettlementService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListenersTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void vehicleListenerDelegatesToTheDetectionService() {
        DetectionService detection = mock(DetectionService.class);
        VehicleDetectedEvent event = new VehicleDetectedEvent("cam", "zone", "ABC123",
                List.of(List.of(0.0, 0.0), List.of(1.0, 0.0), List.of(1.0, 1.0)), null, null, 1.0, 0.9, 1L, "car", null);

        new VehicleEventListener(detection).onVehicleDetected(event);

        verify(detection).process(event);
    }

    @Test
    void paymentListenerMarksTheReferencedFineAsPaidOnline() {
        FineSettlementService fines = mock(FineSettlementService.class);
        UUID fineId = UUID.randomUUID();

        new PaymentEventListener(fines).onPaymentCompleted(
                new PaymentCompletedEvent("p-1", fineId, "FINE", null, BigDecimal.TEN, 1L));

        verify(fines).markPaidOnline(fineId, "p-1", BigDecimal.TEN);
    }

    @Test
    void vehicleDetectedPayloadFromIngestionIsParsedAndValid() throws Exception {
        String json = """
                {"cameraId":"cam-1","zoneId":"z1","plate":null,"vehiclePolygon":[[0,0],[0,10],[10,10],[10,0]],
                 "spotId":"7d0c2a56-6b53-4d4e-9a0c-0f9d1f4f4e11","spotNumber":"A1","iou":1.0,"confidence":0.9,
                 "timestamp":1760000000000,"somethingNew":"ignored"}
                """;

        VehicleDetectedEvent event = mapper.readValue(json, VehicleDetectedEvent.class);

        assertThat(event.plate()).isNull();
        assertThat(event.vehicleClass()).isNull();
        assertThat(event.vehiclePolygon()).hasSize(4);
        assertThat(validator.validate(event)).isEmpty();
    }

    @Test
    void theOptionalViewIdIsReadAndItsAbsenceMeansALegacyObservation() throws Exception {
        String template = "{\"cameraId\":\"c\",\"zoneId\":\"z\",\"plate\":\"ABC123\",\"vehiclePolygon\":[[0,0],[0,1],[1,1]]%s}";

        VehicleDetectedEvent onView = mapper.readValue(
                template.formatted(",\"viewId\":\"7d0c2a56-6b53-4d4e-9a0c-0f9d1f4f4e11\",\"somethingNew\":1"), VehicleDetectedEvent.class);
        VehicleDetectedEvent legacy = mapper.readValue(template.formatted(""), VehicleDetectedEvent.class);
        VehicleDetectedEvent explicitNull = mapper.readValue(template.formatted(",\"viewId\":null"), VehicleDetectedEvent.class);

        assertThat(onView.viewId()).isEqualTo(UUID.fromString("7d0c2a56-6b53-4d4e-9a0c-0f9d1f4f4e11"));
        assertThat(validator.validate(onView)).isEmpty();
        assertThat(legacy.viewId()).isNull();
        assertThat(explicitNull.viewId()).isNull();
        assertThat(validator.validate(legacy)).isEmpty();
    }

    @Test
    void vehicleClassIsReadAndUnknownValuesAreTolerated() throws Exception {
        String template = "{\"cameraId\":\"c\",\"zoneId\":\"z\",\"plate\":\"ABC123\",\"vehiclePolygon\":[[0,0],[0,1],[1,1]],\"vehicleClass\":\"%s\"}";

        VehicleDetectedEvent truck = mapper.readValue(template.formatted("truck"), VehicleDetectedEvent.class);
        VehicleDetectedEvent unheardOf = mapper.readValue(template.formatted("hovercraft"), VehicleDetectedEvent.class);

        assertThat(truck.vehicleClass()).isEqualTo("truck");
        assertThat(validator.validate(truck)).isEmpty();
        assertThat(validator.validate(unheardOf)).isEmpty();
    }

    @Test
    void invalidVehicleDetectedPayloadsAreRejectedByValidation() throws Exception {
        VehicleDetectedEvent noZone = mapper.readValue(
                "{\"cameraId\":\"c\",\"vehiclePolygon\":[[0,0],[0,1],[1,1]]}", VehicleDetectedEvent.class);
        VehicleDetectedEvent noPolygon = mapper.readValue("{\"cameraId\":\"c\",\"zoneId\":\"z\"}", VehicleDetectedEvent.class);
        VehicleDetectedEvent tooFew = mapper.readValue(
                "{\"cameraId\":\"c\",\"zoneId\":\"z\",\"vehiclePolygon\":[[0,0],[0,1]]}", VehicleDetectedEvent.class);
        VehicleDetectedEvent badPair = mapper.readValue(
                "{\"cameraId\":\"c\",\"zoneId\":\"z\",\"vehiclePolygon\":[[0,0],[0,1],[1,1,1]]}", VehicleDetectedEvent.class);

        assertThat(validator.validate(noZone)).isNotEmpty();
        assertThat(validator.validate(noPolygon)).isNotEmpty();
        assertThat(validator.validate(tooFew)).isNotEmpty();
        assertThat(validator.validate(badPair)).isNotEmpty();
    }

    @Test
    void paymentPayloadNeedsAReferenceId() throws Exception {
        PaymentCompletedEvent valid = mapper.readValue("""
                {"paymentId":"p1","referenceId":"7d0c2a56-6b53-4d4e-9a0c-0f9d1f4f4e11","referenceType":"FINE",
                 "userId":"u1","amountSek":700.00,"timestamp":1}
                """, PaymentCompletedEvent.class);
        PaymentCompletedEvent missing = mapper.readValue("{\"paymentId\":\"p1\"}", PaymentCompletedEvent.class);

        assertThat(validator.validate(valid)).isEmpty();
        assertThat(validator.validate(missing)).hasSize(1);
    }

    @Test
    void publishedEventsUseTheCatalogueFieldNames() throws Exception {
        UUID id = UUID.randomUUID();
        var created = mapper.readTree(mapper.writeValueAsString(new ViolationCreatedEvent(
                id, "ABC123", "z1", "Storgatan 1", "z1", "Storgatan 1", "cam", null, id, "A1", ViolationType.WRONG_PERMIT, 30, 1L)));
        var resolved = mapper.readTree(mapper.writeValueAsString(new ViolationResolvedEvent(
                id, "ABC123", "z1", "z1", ResolutionReason.CORRECTED, Instant.parse("2026-10-09T10:00:00Z"))));
        var fine = mapper.readTree(mapper.writeValueAsString(new FineIssuedEvent(
                id, id, "ABC123", null, "z1", null, "z1", null, new BigDecimal("700.00"), 1L, ViolationType.WRONG_VEHICLE_TYPE)));

        assertThat(created.fieldNames()).toIterable().containsExactlyInAnyOrder("violationId", "plate", "zoneId",
                "zoneAddress", "siteId", "siteAddress", "cameraId", "userId", "spotId", "spotNumber", "violationType", "graceMinutes",
                "timestamp");
        assertThat(created.get("violationType").asText()).isEqualTo("WRONG_PERMIT");
        assertThat(resolved.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "violationId", "plate", "zoneId", "siteId", "reason", "occurredAt");
        assertThat(resolved.get("reason").asText()).isEqualTo("CORRECTED");
        assertThat(resolved.get("occurredAt").asText()).isEqualTo("2026-10-09T10:00:00Z");
        assertThat(fine.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "fineId", "violationId", "plate", "userId", "zoneId", "zoneAddress", "siteId", "siteAddress", "amountSek", "timestamp",
                "violationType");
        assertThat(fine.get("violationType").asText()).isEqualTo("WRONG_VEHICLE_TYPE");
        assertThat(fine.get("amountSek").decimalValue()).isEqualByComparingTo("700");
    }

    @Test
    void fineLifecycleEventsHaveExactlyTheAgreedFieldsAndNoFreeTextOrActor() throws Exception {
        UUID id = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-09T10:00:00Z");
        var paid = mapper.readTree(mapper.writeValueAsString(new FinePaidEvent(id, "ABC123", "z1", "z1", new BigDecimal("700.00"), "cash", at)));
        var voided = mapper.readTree(mapper.writeValueAsString(new FineVoidedEvent(id, "ABC123", "z1", "z1", at)));

        assertThat(paid.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "fineId", "plate", "zoneId", "siteId", "amountSek", "method", "occurredAt");
        assertThat(paid.get("fineId").asText()).isEqualTo(id.toString());
        assertThat(paid.get("amountSek").decimalValue()).isEqualByComparingTo("700");
        assertThat(paid.get("method").asText()).isEqualTo("cash");
        assertThat(paid.get("occurredAt").asText()).isEqualTo("2026-10-09T10:00:00Z");
        assertThat(voided.fieldNames()).toIterable().containsExactlyInAnyOrder("fineId", "plate", "zoneId", "siteId", "occurredAt");
        assertThat(voided.get("occurredAt").asText()).isEqualTo("2026-10-09T10:00:00Z");
    }


    @Test
    void sessionEndedResolvesTheViolationsOfThePlateInTheZoneUsingTheEndTime() {
        ViolationService violations = mock(ViolationService.class);
        Instant ended = Instant.parse("2026-10-09T09:55:00Z");

        new SessionEventListener(violations).onSessionEnded(new SessionEndedEvent("abc 123", "z1", ended, Instant.parse("2026-10-09T10:00:00Z")));
        new SessionEventListener(violations).onSessionEnded(new SessionEndedEvent("ABC123", "z1", null, Instant.parse("2026-10-09T10:00:00Z")));

        verify(violations).resolveEnded("ABC123", null, "z1", ended);
        verify(violations).resolveEnded("ABC123", null, "z1", Instant.parse("2026-10-09T10:00:00Z"));
    }

    @Test
    void sessionEndedPayloadFromSessionServiceIsParsedTolerantlyAndNeedsPlateAndZone() throws Exception {
        String json = """
                {"sessionId":"7d0c2a56-6b53-4d4e-9a0c-0f9d1f4f4e11","plate":"ABC123","zoneId":"z1","zoneAddress":"Storgatan 1",
                 "cameraId":"cam-1","userId":null,"startedAt":"2026-10-09T09:00:00Z","endedAt":"2026-10-09T09:55:00Z",
                 "durationMinutes":55,"costSek":12.50,"occurredAt":"2026-10-09T09:55:01Z","somethingNew":true}
                """;

        SessionEndedEvent event = mapper.readValue(json, SessionEndedEvent.class);
        SessionEndedEvent minimal = mapper.readValue("{\"plate\":\"ABC123\",\"zoneId\":\"z1\"}", SessionEndedEvent.class);
        SessionEndedEvent noPlate = mapper.readValue("{\"zoneId\":\"z1\"}", SessionEndedEvent.class);

        assertThat(event.endedAt()).isEqualTo(Instant.parse("2026-10-09T09:55:00Z"));
        assertThat(event.endTime()).isEqualTo(Instant.parse("2026-10-09T09:55:00Z"));
        assertThat(validator.validate(event)).isEmpty();
        assertThat(validator.validate(minimal)).isEmpty();
        assertThat(minimal.endTime()).isNull();
        assertThat(validator.validate(noPlate)).isNotEmpty();
    }

    @Test
    void mapUpdatedOfAnOldProducerEvictsTheSiteOfThatZoneAndTheCataloguePayloadIsParsed() throws Exception {
        SpotCacheService cache = mock(SpotCacheService.class);
        RuleCacheService rules = mock(RuleCacheService.class);
        when(rules.siteIdOf("z1")).thenReturn("s1");
        MapUpdatedEvent event = mapper.readValue("""
                {"zoneId":"z1","versionNumber":7,"spotCount":12,"changeReason":"rollback","timestamp":1760000000000,"x":1}
                """, MapUpdatedEvent.class);

        new MapEventListener(cache, rules).onMapUpdated(event);

        verify(cache).evict("s1");
        verify(rules).evict("s1", "z1");
        assertThat(event.versionNumber()).isEqualTo(7);
        assertThat(validator.validate(event)).isEmpty();
        assertThat(validator.validate(mapper.readValue("{\"versionNumber\":1}", MapUpdatedEvent.class))).isNotEmpty();
    }

    @Test
    void mapUpdatedPerSiteEvictsThatSite() throws Exception {
        SpotCacheService cache = mock(SpotCacheService.class);
        RuleCacheService rules = mock(RuleCacheService.class);
        MapUpdatedEvent event = mapper.readValue("""
                {"siteId":"s1","zoneId":null,"versionNumber":3,"spotCount":4,"changeReason":"site rule","timestamp":1}
                """, MapUpdatedEvent.class);

        new MapEventListener(cache, rules).onMapUpdated(event);

        verify(cache).evict("s1");
        verify(rules).evict("s1", null);
        assertThat(validator.validate(event)).isEmpty();
    }

    @Test
    void anObservationOrASessionNeedsASiteOrAZone() throws Exception {
        VehicleDetectedEvent siteOnly = mapper.readValue(
                "{\"cameraId\":\"c\",\"siteId\":\"s1\",\"vehiclePolygon\":[[0,0],[0,1],[1,1]]}", VehicleDetectedEvent.class);
        SessionEndedEvent sessionAtSite = mapper.readValue("{\"plate\":\"ABC123\",\"siteId\":\"s1\",\"zoneId\":null}", SessionEndedEvent.class);
        SessionEndedEvent nowhere = mapper.readValue("{\"plate\":\"ABC123\"}", SessionEndedEvent.class);

        assertThat(siteOnly.siteId()).isEqualTo("s1");
        assertThat(validator.validate(siteOnly)).isEmpty();
        assertThat(validator.validate(sessionAtSite)).isEmpty();
        assertThat(validator.validate(nowhere)).isNotEmpty();
    }

    @Test
    void sessionEndedAtASiteResolvesTheViolationsThere() {
        ViolationService violations = mock(ViolationService.class);
        Instant ended = Instant.parse("2026-10-09T09:55:00Z");

        new SessionEventListener(violations).onSessionEnded(new SessionEndedEvent("ABC123", null, "s1", ended, null));

        verify(violations).resolveEnded("ABC123", "s1", null, ended);
    }
}
