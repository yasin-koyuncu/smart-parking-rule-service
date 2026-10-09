package com.parkview.ruleengine.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkview.ruleengine.dto.event.FineIssuedEvent;
import com.parkview.ruleengine.dto.event.PaymentCompletedEvent;
import com.parkview.ruleengine.dto.event.VehicleDetectedEvent;
import com.parkview.ruleengine.dto.event.ViolationCreatedEvent;
import com.parkview.ruleengine.dto.event.ViolationResolvedEvent;
import com.parkview.ruleengine.domain.ResolutionReason;
import com.parkview.ruleengine.domain.ViolationType;
import com.parkview.ruleengine.service.DetectionService;
import com.parkview.ruleengine.service.FineService;
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

class ListenersTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void vehicleListenerDelegatesToTheDetectionService() {
        DetectionService detection = mock(DetectionService.class);
        VehicleDetectedEvent event = new VehicleDetectedEvent("cam", "zone", "ABC123",
                List.of(List.of(0.0, 0.0), List.of(1.0, 0.0), List.of(1.0, 1.0)), null, null, 1.0, 0.9, 1L);

        new VehicleEventListener(detection).onVehicleDetected(event);

        verify(detection).process(event);
    }

    @Test
    void paymentListenerMarksTheReferencedFineAsPaid() {
        FineService fines = mock(FineService.class);
        UUID fineId = UUID.randomUUID();

        new PaymentEventListener(fines).onPaymentCompleted(
                new PaymentCompletedEvent("p-1", fineId, "FINE", null, BigDecimal.TEN, 1L));

        verify(fines).markPaid(fineId);
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
        assertThat(event.vehiclePolygon()).hasSize(4);
        assertThat(validator.validate(event)).isEmpty();
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
                id, "ABC123", "z1", "Storgatan 1", "cam", null, id, "A1", ViolationType.WRONG_PERMIT, 30, 1L)));
        var resolved = mapper.readTree(mapper.writeValueAsString(new ViolationResolvedEvent(
                id, "ABC123", "z1", ResolutionReason.CORRECTED, Instant.parse("2026-10-09T10:00:00Z"))));
        var fine = mapper.readTree(mapper.writeValueAsString(new FineIssuedEvent(
                id, id, "ABC123", null, "z1", null, new BigDecimal("700.00"), 1L)));

        assertThat(created.fieldNames()).toIterable().containsExactlyInAnyOrder("violationId", "plate", "zoneId",
                "zoneAddress", "cameraId", "userId", "spotId", "spotNumber", "violationType", "graceMinutes", "timestamp");
        assertThat(created.get("violationType").asText()).isEqualTo("WRONG_PERMIT");
        assertThat(resolved.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "violationId", "plate", "zoneId", "reason", "occurredAt");
        assertThat(resolved.get("reason").asText()).isEqualTo("CORRECTED");
        assertThat(resolved.get("occurredAt").asText()).isEqualTo("2026-10-09T10:00:00Z");
        assertThat(fine.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "fineId", "violationId", "plate", "userId", "zoneId", "zoneAddress", "amountSek", "timestamp");
        assertThat(fine.get("amountSek").decimalValue()).isEqualByComparingTo("700");
    }
}
