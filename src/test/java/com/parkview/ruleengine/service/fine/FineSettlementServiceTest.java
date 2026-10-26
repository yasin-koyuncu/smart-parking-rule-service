package com.parkview.ruleengine.service.fine;

import com.parkview.ruleengine.config.RabbitConfig;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.FineAction;
import com.parkview.ruleengine.domain.type.PaidMethod;
import com.parkview.ruleengine.dto.event.FinePaidEvent;
import com.parkview.ruleengine.engine.EngineMetrics;
import com.parkview.ruleengine.service.TestFixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
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

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final EngineMetrics metrics = new EngineMetrics(registry);
    private final FineSettlementService service = new FineSettlementService(fines, actions, closer, fineEvents, metrics, clock);
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
        assertThat(event).isEqualTo(new FinePaidEvent(fine.getId(), TestFixtures.PLATE, TestFixtures.ZONE, TestFixtures.ZONE,
                new BigDecimal("700.00"), "online", NOW));
    }

    @Test
    void aRepeatedOrRedeliveredOnlinePaymentPublishesNothingAndWritesNoSecondRow() {
        Fine fine = unpaidFine();

        service.markPaidOnline(fine.getId(), "pay-1");
        // the repository now knows the first payment's audit row
        when(actions.existsForPayment(fine.getId(), "paid_online", "pay-1")).thenReturn(true);
        service.markPaidOnline(fine.getId(), "pay-1");

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
        verifyNoInteractions(events);
        // money was taken for a fine that was already settled by hand: a person has to refund it
        FineAction review = savedAction();
        assertThat(review.getAction()).isEqualTo("payment_needs_review");
        assertThat(review.getActorId()).isNull();
        assertThat(review.getReason()).contains("already paid");
        assertThat(review.getDetails()).containsEntry("paymentId", "pay-1").containsEntry("causes", List.of("already_paid"));
        assertThat(registry.counter("ruleengine.payments.needs_review", "cause", "already_paid").count()).isEqualTo(1.0);
    }

    @Test
    void anOnlinePaymentForAVoidedFineLeavesItVoidedAndSaysNothing() {
        Fine fine = unpaidFine();
        management.voidFine(fine.getId(), "Plate misread", ACTOR);
        clearInvocations(events, actions);

        service.markPaidOnline(fine.getId(), "pay-1");

        assertThat(fine.isPaid()).isFalse();
        assertThat(fine.isVoided()).isTrue();
        verifyNoInteractions(events);
        FineAction review = savedAction();
        assertThat(review.getAction()).isEqualTo("payment_needs_review");
        assertThat(review.getReason()).contains("voided");
        assertThat(review.getDetails()).containsEntry("paymentId", "pay-1").containsEntry("causes", List.of("voided"));
        assertThat(registry.counter("ruleengine.payments.needs_review", "cause", "voided").count()).isEqualTo(1.0);
    }

    @Test
    void anOnlinePaymentForAnUnknownFineDoesNotFailAndPublishesNothing() {
        UUID id = UUID.randomUUID();
        when(fines.findByIdForUpdate(id)).thenReturn(Optional.empty());

        service.markPaidOnline(id, "pay-1");

        verifyNoInteractions(events, actions);
    }


    @Test
    void aRedeliveredPaymentForAVoidedFineWritesNoSecondReviewRow() {
        Fine fine = unpaidFine();
        management.voidFine(fine.getId(), "Plate misread", ACTOR);
        clearInvocations(events, actions);
        when(actions.existsForPayment(fine.getId(), "payment_needs_review", "pay-1")).thenReturn(true);

        service.markPaidOnline(fine.getId(), "pay-1");

        verifyNoInteractions(events);
        verify(actions, org.mockito.Mockito.never()).save(any(FineAction.class));
        assertThat(registry.counter("ruleengine.payments.needs_review", "cause", "voided").count()).isZero();
    }

    @Test
    void aSecondDifferentPaymentForAnOnlinePaidFineIsFlaggedAsAlreadyPaid() {
        Fine fine = unpaidFine();
        service.markPaidOnline(fine.getId(), "pay-1");
        clearInvocations(events, actions);

        service.markPaidOnline(fine.getId(), "pay-2");

        verifyNoInteractions(events);
        assertThat(savedAction().getDetails()).containsEntry("paymentId", "pay-2").containsEntry("causes", List.of("already_paid"));
    }

    @Test
    void aPaymentOfAnotherAmountStillSettlesTheFineButIsFlagged() {
        Fine fine = unpaidFine();   // 700 SEK

        service.markPaidOnline(fine.getId(), "pay-1", new BigDecimal("450.00"));

        assertThat(fine.isPaid()).isTrue();
        verify(events, times(1)).enqueue(any(), any(), eq(RabbitConfig.VIOLATION_EXCHANGE), eq("fine.paid"), any());
        org.mockito.ArgumentCaptor<FineAction> saved = org.mockito.ArgumentCaptor.forClass(FineAction.class);
        verify(actions, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(FineAction::getAction).containsExactly("paid_online", "payment_needs_review");
        FineAction review = saved.getAllValues().get(1);
        assertThat(review.getReason()).contains("amount mismatch");
        assertThat(review.getDetails()).containsEntry("paidAmountSek", "450.00").containsEntry("fineAmountSek", "700.00")
                .containsEntry("causes", List.of("amount_mismatch"));
        assertThat(registry.counter("ruleengine.payments.needs_review", "cause", "amount_mismatch").count()).isEqualTo(1.0);
    }

    @Test
    void aPaymentOfTheRightAmountOrWithoutAnAmountIsNotFlagged() {
        Fine fine = unpaidFine();
        service.markPaidOnline(fine.getId(), "pay-1", new BigDecimal("700"));
        Fine other = unpaidFine();
        service.markPaidOnline(other.getId(), "pay-2", null);

        verify(actions, times(2)).save(any(FineAction.class));   // the two paid_online rows only
        assertThat(registry.counter("ruleengine.payments.needs_review", "cause", "amount_mismatch").count()).isZero();
    }

    @Test
    void aPaymentForAnUnknownFineIsCounted() {
        UUID id = UUID.randomUUID();
        when(fines.findByIdForUpdate(id)).thenReturn(Optional.empty());

        service.markPaidOnline(id, "pay-1", BigDecimal.TEN);

        assertThat(registry.counter("ruleengine.payments.needs_review", "cause", "unknown_fine").count()).isEqualTo(1.0);
    }
}
