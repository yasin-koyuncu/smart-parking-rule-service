package com.parkview.ruleengine.service;

import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.config.RabbitConfig;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.PaidMethod;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.dto.event.FineIssuedEvent;
import com.parkview.ruleengine.dto.event.FinePaidEvent;
import com.parkview.ruleengine.dto.event.FineVoidedEvent;
import com.parkview.ruleengine.messaging.EventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * The fine events of the catalogue ({@code fine.issued}, {@code fine.paid}, {@code fine.voided}): the payload of each
 * and the routing key it goes out with. {@link EventPublisher} sends them after the surrounding transaction commits;
 * the free text reason of a void never becomes part of an event.
 */
@Component
@RequiredArgsConstructor
class FineEvents {

    private final EventPublisher events;
    private final ZoneDirectory zones;

    void issued(Fine fine, Violation violation, UUID owner, Instant at) {
        events.publish(RabbitConfig.VIOLATION_EXCHANGE, RabbitConfig.FINE_ISSUED_KEY,
                new FineIssuedEvent(fine.getId(), violation.getId(), fine.getPlate(), owner, fine.getZoneId(),
                        zones.addressOf(fine.getZoneId()).orElse(null), fine.getAmountSek(), at.toEpochMilli(),
                        violation.getViolationType()));
    }

    void paid(Fine fine, PaidMethod method, Instant at) {
        events.publish(RabbitConfig.VIOLATION_EXCHANGE, RabbitConfig.FINE_PAID_KEY,
                new FinePaidEvent(fine.getId(), fine.getPlate(), fine.getZoneId(), fine.getAmountSek(), method.wire(), at));
    }

    void voided(Fine fine, Instant at) {
        events.publish(RabbitConfig.VIOLATION_EXCHANGE, RabbitConfig.FINE_VOIDED_KEY,
                new FineVoidedEvent(fine.getId(), fine.getPlate(), fine.getZoneId(), at));
    }
}
