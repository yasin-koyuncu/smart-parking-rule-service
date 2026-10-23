package com.parkview.ruleengine.service.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkview.ruleengine.domain.OutboxEvent;
import com.parkview.ruleengine.repository.OutboxEventRepository;
import com.parkview.ruleengine.web.CorrelationIdFilter;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Writes every event this service publishes to the outbox, in the transaction of the state change it describes (it
 * refuses to run without one). {@link OutboxRelay} publishes the rows afterwards, so an event is neither lost when the
 * broker is down at that moment nor announced for a change that was rolled back.
 */
@Service
@RequiredArgsConstructor
public class OutboxService {

    public static final String AGGREGATE_VIOLATION = "violation";
    public static final String AGGREGATE_FINE = "fine";

    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * @param aggregateType {@code violation} or {@code fine}
     * @param aggregateId   the id of that violation or fine: events of one aggregate are published in the order they were written
     * @param payload       serialised with the application's ObjectMapper, exactly as the former direct publish did
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent enqueue(String aggregateType, UUID aggregateId, String exchange, String routingKey, Object payload) {
        try {
            return outbox.save(OutboxEvent.create(aggregateType, aggregateId.toString(), exchange, routingKey,
                    objectMapper.writeValueAsString(payload), MDC.get(CorrelationIdFilter.MDC_KEY), clock.instant()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise " + payload.getClass().getSimpleName(), e);
        }
    }
}
