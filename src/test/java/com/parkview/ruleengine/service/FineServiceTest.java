package com.parkview.ruleengine.service;

import com.parkview.ruleengine.client.PlateOwnerLookup;
import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.config.RabbitConfig;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.ViolationType;
import com.parkview.ruleengine.dto.event.FineIssuedEvent;
import com.parkview.ruleengine.messaging.EventPublisher;
import com.parkview.ruleengine.repository.FineRepository;
import com.parkview.ruleengine.web.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FineServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    private final FineRepository fines = mock(FineRepository.class);
    private final PlateOwnerLookup owners = mock(PlateOwnerLookup.class);
    private final ZoneDirectory zones = mock(ZoneDirectory.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private FineService service;

    @BeforeEach
    void setUp() {
        service = new FineService(fines, owners, zones, events, TestFixtures.properties(), Clock.fixed(NOW, ZoneOffset.UTC));
        when(fines.save(any(Fine.class))).thenAnswer(inv -> {
            Fine f = inv.getArgument(0);
            ReflectionTestUtils.setField(f, "id", UUID.randomUUID());
            return f;
        });
        when(owners.ownerOf(any())).thenReturn(Optional.empty());
        when(zones.addressOf(any())).thenReturn(Optional.empty());
    }

    @ParameterizedTest
    @CsvSource({"NO_PARKING,900.00", "OVERSTAY,450.00", "WRONG_PERMIT,700.00", "BOUNDARY_EXCEEDED,900.00"})
    void fineAmountFollowsTheViolationType(ViolationType type, String amount) {
        Violation violation = TestFixtures.violation(type, NOW.minusSeconds(900), NOW.minusSeconds(60));

        Fine fine = service.issueFine(violation);

        assertThat(fine.getAmountSek()).isEqualByComparingTo(amount);
        assertThat(fine.getAmountSek().scale()).isEqualTo(2);
        assertThat(fine.getReason()).isEqualTo(type);
        assertThat(fine.isPaid()).isFalse();
        assertThat(fine.getIssuedAt()).isEqualTo(NOW);
    }

    @Test
    void issuingLinksTheViolationAndPublishesFineIssuedPerCatalogue() {
        UUID owner = UUID.randomUUID();
        Violation violation = TestFixtures.violation(ViolationType.WRONG_PERMIT, NOW.minusSeconds(900), NOW.minusSeconds(60));
        when(owners.ownerOf(TestFixtures.PLATE)).thenReturn(Optional.of(owner));
        when(zones.addressOf(TestFixtures.ZONE)).thenReturn(Optional.of("Storgatan 1"));

        Fine fine = service.issueFine(violation);

        assertThat(violation.getFineId()).isEqualTo(fine.getId());
        assertThat(violation.getFineIssuedAt()).isEqualTo(NOW);
        assertThat(violation.isActive()).isFalse();
        assertThat(fine.getUserId()).isEqualTo(owner);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq(RabbitConfig.VIOLATION_EXCHANGE), eq("fine.issued"), payload.capture());
        FineIssuedEvent event = (FineIssuedEvent) payload.getValue();
        assertThat(event.fineId()).isEqualTo(fine.getId());
        assertThat(event.violationId()).isEqualTo(violation.getId());
        assertThat(event.plate()).isEqualTo(TestFixtures.PLATE);
        assertThat(event.userId()).isEqualTo(owner);
        assertThat(event.zoneId()).isEqualTo(TestFixtures.ZONE);
        assertThat(event.zoneAddress()).isEqualTo("Storgatan 1");
        assertThat(event.amountSek()).isEqualByComparingTo(BigDecimal.valueOf(700));
        assertThat(event.timestamp()).isEqualTo(NOW.toEpochMilli());
    }

    @Test
    void theOwnerStoredOnTheViolationWinsOverALookup() {
        UUID owner = UUID.randomUUID();
        Violation violation = Violation.open(UUID.randomUUID(), TestFixtures.ZONE, TestFixtures.PLATE, owner,
                ViolationType.NO_PARKING, NOW.minusSeconds(900), NOW.minusSeconds(60));

        assertThat(service.issueFine(violation).getUserId()).isEqualTo(owner);
        verifyNoInteractions(owners);
    }

    @Test
    void markPaidIsIdempotent() {
        Violation violation = TestFixtures.violation(ViolationType.NO_PARKING, NOW, NOW);
        Fine fine = service.issueFine(violation);
        when(fines.findById(fine.getId())).thenReturn(Optional.of(fine));

        service.markPaid(fine.getId());
        service.markPaid(fine.getId());

        assertThat(fine.isPaid()).isTrue();
    }

    @Test
    void markPaidForAnUnknownFineDoesNotFail() {
        UUID id = UUID.randomUUID();
        when(fines.findById(id)).thenReturn(Optional.empty());

        service.markPaid(id);
    }

    @Test
    void getUnknownFineIsNotFound() {
        UUID id = UUID.randomUUID();
        when(fines.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(ResourceNotFoundException.class);
    }
}
