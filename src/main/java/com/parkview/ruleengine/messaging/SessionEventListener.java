package com.parkview.ruleengine.messaging;

import com.parkview.ruleengine.config.RabbitConfig;
import com.parkview.ruleengine.dto.event.SessionEndedEvent;
import com.parkview.ruleengine.service.violation.ViolationService;
import com.parkview.ruleengine.util.Plates;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code session.ended} from session-service: the vehicle has left, so its open violations in that zone are
 * resolved as LEFT instead of waiting for the stale sweep (and, above all, are not fined meanwhile). Redelivery is harmless.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionEventListener {

    private final ViolationService violationService;

    @RabbitListener(queues = RabbitConfig.SESSION_ENDED_QUEUE)
    public void onSessionEnded(@Payload @Valid SessionEndedEvent event) {
        String plate = Plates.normalize(event.plate());
        if (plate == null) {
            return;
        }
        int resolved = violationService.resolveEnded(plate, event.zoneId(), event.endTime());
        if (resolved > 0) {
            log.debug("Session ended in zone {}: {} violations resolved as LEFT", event.zoneId(), resolved);
        }
    }
}
