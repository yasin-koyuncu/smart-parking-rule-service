package com.parkview.ruleengine.messaging;

import com.parkview.ruleengine.config.RabbitMQConfig;
import com.parkview.ruleengine.model.VehicleDetectedEvent;
import com.parkview.ruleengine.service.BoundaryService;
import com.parkview.ruleengine.service.ViolationService;
import com.parkview.ruleengine.service.SpotCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Lyssnar på VehicleDetected-events från edge-enheter via RabbitMQ.
 * Kör boundary-check och skapar violations vid behov.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VehicleEventListener {

    private final BoundaryService  boundaryService;
    private final ViolationService violationService;
    private final SpotCacheService spotCacheService;

    @RabbitListener(queues = RabbitMQConfig.VEHICLE_DETECTED_QUEUE)
    public void onVehicleDetected(VehicleDetectedEvent event) {
        log.debug("VehicleDetected: {} i zon {}", event.getPlate(), event.getZoneId());

        if (event.getVehiclePolygon() == null || event.getVehiclePolygon().isEmpty()) {
            log.warn("Tomt fordonpolygon i event — ignorerar");
            return;
        }

        // Hämta alla spots för zonen (cachat)
        var spots = spotCacheService.getSpotsForZone(event.getZoneId());
        if (spots.isEmpty()) {
            log.debug("Inga sparade spots för zon {} — hoppar över boundary-check", event.getZoneId());
            return;
        }

        // Kör boundary-check mot alla spots
        for (var spot : spots) {
            var result = boundaryService.check(
                    event.getVehiclePolygon(),
                    spot,
                    event.getPlate()
            );

            if (result.getViolation() != null) {
                violationService.createIfNew(result, spot);
            }
        }
    }
}