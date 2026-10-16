package com.parkview.ruleengine.service;

import com.parkview.ruleengine.client.PlateOwnerDirectory;
import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.config.RabbitConfig;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.FineAction;
import com.parkview.ruleengine.domain.ViolationType;
import com.parkview.ruleengine.messaging.EventPublisher;
import com.parkview.ruleengine.repository.FineActionRepository;
import com.parkview.ruleengine.repository.FineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Shared mocks, fixed clock and helpers of the fine service tests; one subclass per service. */
abstract class FineServiceTestBase {

    protected static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");
    protected static final UUID ACTOR = UUID.fromString("2f6c1e0a-3b7d-4c55-8f1e-6a0b9d7c1234");

    protected final FineRepository fines = mock(FineRepository.class);
    protected final FineActionRepository actions = mock(FineActionRepository.class);
    protected final PlateOwnerDirectory owners = mock(PlateOwnerDirectory.class);
    protected final ZoneDirectory zones = mock(ZoneDirectory.class);
    protected final EventPublisher events = mock(EventPublisher.class);
    protected final FineEvents fineEvents = new FineEvents(events, zones);
    protected final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    protected final FineIssuingService issuing =
            new FineIssuingService(fines, owners, fineEvents, TestFixtures.properties(), clock);

    @BeforeEach
    void stubRepositories() {
        when(fines.save(any(Fine.class))).thenAnswer(inv -> {
            Fine f = inv.getArgument(0);
            ReflectionTestUtils.setField(f, "id", UUID.randomUUID());
            return f;
        });
        when(owners.ownerOf(any())).thenReturn(Optional.empty());
        when(zones.addressOf(any())).thenReturn(Optional.empty());
    }

    /** An issued, unpaid fine of 700 SEK that the repository hands out (and locks) by id; issuing events are forgotten. */
    protected Fine unpaidFine() {
        Fine fine = issuing.issueFine(TestFixtures.violation(ViolationType.WRONG_PERMIT, NOW.minusSeconds(900), NOW.minusSeconds(60)));
        when(fines.findByIdForUpdate(fine.getId())).thenReturn(Optional.of(fine));
        clearInvocations(events);
        return fine;
    }

    protected FineAction savedAction() {
        ArgumentCaptor<FineAction> captor = ArgumentCaptor.forClass(FineAction.class);
        verify(actions).save(captor.capture());
        return captor.getValue();
    }

    protected Object publishedPayload(String routingKey) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq(RabbitConfig.VIOLATION_EXCHANGE), eq(routingKey), payload.capture());
        return payload.getValue();
    }
}
