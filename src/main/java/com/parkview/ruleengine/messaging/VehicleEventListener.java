package com.parkview.ruleengine.messaging;

import com.parkview.ruleengine.config.RabbitConfig;
import com.parkview.ruleengine.dto.event.VehicleDetectedEvent;
import com.parkview.ruleengine.service.DetectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code vehicle.detected}. Invalid payloads are rejected to the dead-letter queue; other
 * failures propagate so the listener retries before dead-lettering.
 */
@Component
@RequiredArgsConstructor
public class VehicleEventListener {

    private final DetectionService detectionService;

    @RabbitListener(queues = RabbitConfig.VEHICLE_DETECTED_QUEUE)
    public void onVehicleDetected(@Payload @Valid VehicleDetectedEvent event) {
        detectionService.process(event);
    }
}
