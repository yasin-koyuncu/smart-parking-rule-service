package com.parkview.ruleengine.service.fine;

import com.parkview.ruleengine.config.RabbitConfig;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.FineAction;
import com.parkview.ruleengine.domain.type.PaidMethod;
import com.parkview.ruleengine.dto.event.FinePaidEvent;
import com.parkview.ruleengine.service.TestFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FineSettlementServiceTest extends FineServiceTestBase {

    private final FineSettlementService service = new FineSettlementService(fines, actions, closer, fineEvents, clock);
    private final FineManagementService management = new FineManagementService(fines, actions, disputes, closer, fineEvents, clock);

    // ---- online payment (payment.completed.fine) ----

    @Test
    void theOnlinePaymentMarksTheFineWritesAnActorlessAuditRowAndPublishesFinePaid() {
        Fine fine = unpaidFine();

        service.markPaidOnline(fine.getId(), "pay-1");

        assertThat(fine.isPaid()).isTrue();
        assertThat(fine.getPaidAt()).isEqualTo(NOW);
        assertThat(fine.getPaidMethod()).isEqualTo("online");
        assertThat(fine.getPaidBy()).isNull();

        FineAction entry = savedAction();
        assertThat(entry.getAction()).isEqualTo("paid_online");
        assertThat(entry.getActorId()).isNull();
        assertThat(entry.getReason()).isNull();
        assertThat(entry.getDetails()).isEqualTo(Map.of("method", "online", "paymentId", "pay-1"));

        FinePaidEvent event = (FinePaidEvent) publishedPayload("fine.paid");
        assertThat(event).isEqualTo(new FinePaidEvent(fine.getId(), TestFixtures.PLATE, TestFixtures.ZONE,
                new BigDecimal("700.00"), "online", NOW));
    }

    @Test
    void aRepeatedOrRedeliveredOnlinePaymentPublishesNothingAndWritesNoSecondRow() {
        Fine fine = unpaidFine();

        service.markPaidOnline(fine.getId(), "pay-1");
        service.markPaidOnline(fine.getId(), "pay-1");
        service.markPaidOnline(fine.getId(), "pay-2");

        assertThat(fine.isPaid()).isTrue();
        verify(actions, times(1)).save(any(FineAction.class));
        verify(events, times(1)).enqueue(any(), any(), eq(RabbitConfig.VIOLATION_EXCHANGE), eq("fine.paid"), any());
    }

    @Test
    void anOnlinePaymentWithoutPaymentIdStillRecordsTheMethod() {
        Fine fine = unpaidFine();

        service.markPaidOnline(fine.getId(), null);

        assertThat(savedAction().getDetails()).isEqualTo(Map.of("method", "online"));
    }

    @Test
    void anOnlinePaymentForAFineThatAnOperatorAlreadyMarkedAsPaidIsNotPublishedAgain() {
        Fine fine = unpaidFine();
        management.markPaidManually(fine.getId(), "Paid at the counter", PaidMethod.CASH, ACTOR);
        clearInvocations(events, actions);

        service.markPaidOnline(fine.getId(), "pay-1");

        assertThat(fine.getPaidMethod()).isEqualTo("cash");
        verifyNoInteractions(events, actions);
    }

    @Test
    void anOnlinePaymentForAVoidedFineLeavesItVoidedAndSaysNothing() {
        Fine fine = unpaidFine();
        management.voidFine(fine.getId(), "Plate misread", ACTOR);
        clearInvocations(events, actions);

        service.markPaidOnline(fine.getId(), "pay-1");

        assertThat(fine.isPaid()).isFalse();
        assertThat(fine.isVoided()).isTrue();
        verifyNoInteractions(events, actions);
    }

    @Test
    void anOnlinePaymentForAnUnknownFineDoesNotFailAndPublishesNothing() {
        UUID id = UUID.randomUUID();
        when(fines.findByIdForUpdate(id)).thenReturn(Optional.empty());

        service.markPaidOnline(id, "pay-1");

        verifyNoInteractions(events, actions);
    }
}
