package com.parkview.ruleengine.service.fine;

import com.parkview.ruleengine.client.PlateOwnerDirectory;
import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.config.RabbitConfig;
import com.parkview.ruleengine.domain.Dispute;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.type.PaidMethod;
import com.parkview.ruleengine.dto.event.FineDisputeDecidedEvent;
import com.parkview.ruleengine.dto.event.FineDisputedEvent;
import com.parkview.ruleengine.dto.event.FineIssuedEvent;
import com.parkview.ruleengine.dto.event.FinePaidEvent;
import com.parkview.ruleengine.dto.event.FineVoidedEvent;
import com.parkview.ruleengine.service.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * The fine events of the catalogue ({@code fine.issued}, {@code fine.paid}, {@code fine.voided}, {@code fine.disputed},
 * {@code fine.dispute.decided}): the payload of each and the routing key it goes out with. They are written to the outbox
 * in the transaction of the change ({@link OutboxService}) and published by the relay; all events of one fine share the
 * aggregate id, so they go out in order. Free text (void reason, dispute message, decision note) never becomes part of an event.
 */
@Component
@RequiredArgsConstructor
class FineEvents {

    private final OutboxService outbox;
    private final ZoneDirectory zones;
    private final PlateOwnerDirectory plateOwners;

    void issued(Fine fine, Violation violation, UUID owner, Instant at) {
        publish(fine.getId(), RabbitConfig.FINE_ISSUED_KEY,
                new FineIssuedEvent(fine.getId(), violation.getId(), fine.getPlate(), owner, fine.getZoneId(),
                        zones.addressOf(fine.getZoneId()).orElse(null), fine.getAmountSek(), at.toEpochMilli(),
                        violation.getViolationType()));
    }

    void paid(Fine fine, PaidMethod method, Instant at) {
        publish(fine.getId(), RabbitConfig.FINE_PAID_KEY,
                new FinePaidEvent(fine.getId(), fine.getPlate(), fine.getZoneId(), fine.getAmountSek(), method.wire(), at));
    }

    void voided(Fine fine, Instant at) {
        publish(fine.getId(), RabbitConfig.FINE_VOIDED_KEY,
                new FineVoidedEvent(fine.getId(), fine.getPlate(), fine.getZoneId(), at));
    }

    void disputed(Dispute dispute, Instant at) {
        publish(dispute.getFineId(), RabbitConfig.FINE_DISPUTED_KEY,
                new FineDisputedEvent(dispute.getId(), dispute.getFineId(), dispute.getPlate(), dispute.getZoneId(),
                        dispute.getChannel().wire(), at));
    }

    /** {@code userId} is the owner of the plate when known: the fine's own owner, else whoever has registered the plate now. */
    void disputeDecided(Dispute dispute, Fine fine, Instant at) {
        UUID owner = fine.getUserId() != null ? fine.getUserId() : plateOwners.ownerOf(fine.getPlate()).orElse(null);
        publish(dispute.getFineId(), RabbitConfig.FINE_DISPUTE_DECIDED_KEY,
                new FineDisputeDecidedEvent(dispute.getId(), dispute.getFineId(), dispute.getPlate(), dispute.getZoneId(),
                        owner, dispute.getStatus().wire(), at, dispute.getDecisionNote()));
    }

    private void publish(UUID fineId, String routingKey, Object payload) {
        outbox.enqueue(OutboxService.AGGREGATE_FINE, fineId, RabbitConfig.VIOLATION_EXCHANGE, routingKey, payload);
    }
}
