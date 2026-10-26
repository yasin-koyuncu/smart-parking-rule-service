package com.parkview.ruleengine.service.fine;

import com.parkview.ruleengine.config.RabbitConfig;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.type.ViolationType;
import com.parkview.ruleengine.dto.event.FineIssuedEvent;
import com.parkview.ruleengine.service.TestFixtures;
import com.parkview.ruleengine.service.outbox.OutboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FineIssuingServiceTest extends FineServiceTestBase {

    private final FineIssuingService service = issuing;

    @ParameterizedTest
    @CsvSource({"NO_PARKING,900.00", "OVERSTAY,450.00", "WRONG_PERMIT,700.00", "BOUNDARY_EXCEEDED,900.00"})
    void fineAmountFollowsTheViolationType(ViolationType type, String amount) {
        Violation violation = TestFixtures.violation(type, NOW.minusSeconds(900), NOW.minusSeconds(60));

        Fine fine = service.issueFine(violation);

        assertThat(fine.getAmountSek()).isEqualByComparingTo(amount);
        assertThat(fine.getAmountSek().scale()).isEqualTo(2);
        assertThat(fine.getReason()).isEqualTo(type);
        assertThat(fine.isPaid()).isFalse();
        assertThat(fine.isVoided()).isFalse();
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
        verify(events).enqueue(eq(OutboxService.AGGREGATE_FINE), eq(fine.getId()), eq(RabbitConfig.VIOLATION_EXCHANGE), eq("fine.issued"),
                payload.capture());
        FineIssuedEvent event = (FineIssuedEvent) payload.getValue();
        assertThat(event.fineId()).isEqualTo(fine.getId());
        assertThat(event.violationId()).isEqualTo(violation.getId());
        assertThat(event.plate()).isEqualTo(TestFixtures.PLATE);
        assertThat(event.userId()).isEqualTo(owner);
        assertThat(event.zoneId()).isEqualTo(TestFixtures.ZONE);
        assertThat(event.zoneAddress()).isEqualTo("Storgatan 1");
        assertThat(event.amountSek()).isEqualByComparingTo(BigDecimal.valueOf(700));
        assertThat(event.timestamp()).isEqualTo(NOW.toEpochMilli());
        assertThat(event.violationType()).isEqualTo(ViolationType.WRONG_PERMIT);
    }

    @Test
    void theEvidenceIsFrozenForTheFineBeforeItsEventIsQueued() {
        Violation violation = TestFixtures.violation(ViolationType.OVERSTAY, NOW.minusSeconds(900), NOW.minusSeconds(60));

        Fine fine = service.issueFine(violation);

        InOrder order = inOrder(evidence, events);
        order.verify(evidence).record(fine, violation);
        order.verify(events).enqueue(eq(OutboxService.AGGREGATE_FINE), eq(fine.getId()), any(), eq("fine.issued"), any());
    }

    @Test
    void whenTheEvidenceCannotBeWrittenNoEventIsQueuedAndTheErrorReachesTheTransaction() {
        Violation violation = TestFixtures.violation(ViolationType.OVERSTAY, NOW.minusSeconds(900), NOW.minusSeconds(60));
        when(evidence.record(any(), any())).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> service.issueFine(violation)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(events);
    }

    @Test
    void theOwnerStoredOnTheViolationWinsOverALookup() {
        UUID owner = UUID.randomUUID();
        Violation violation = Violation.open(UUID.randomUUID(), TestFixtures.ZONE, TestFixtures.ZONE, TestFixtures.PLATE, owner,
                ViolationType.NO_PARKING, NOW.minusSeconds(900), NOW.minusSeconds(60));

        assertThat(service.issueFine(violation).getUserId()).isEqualTo(owner);
        verifyNoInteractions(owners);
    }
}
