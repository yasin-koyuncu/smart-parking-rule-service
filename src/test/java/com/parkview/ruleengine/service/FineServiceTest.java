package com.parkview.ruleengine.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.parkview.ruleengine.client.PlateOwnerLookup;
import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.config.RabbitConfig;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.FineAction;
import com.parkview.ruleengine.domain.PaidMethod;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.ViolationType;
import com.parkview.ruleengine.dto.event.FineIssuedEvent;
import com.parkview.ruleengine.dto.event.FinePaidEvent;
import com.parkview.ruleengine.dto.event.FineVoidedEvent;
import com.parkview.ruleengine.messaging.EventPublisher;
import com.parkview.ruleengine.repository.FineActionRepository;
import com.parkview.ruleengine.repository.FineRepository;
import com.parkview.ruleengine.web.ConflictException;
import com.parkview.ruleengine.web.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FineServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");
    private static final UUID ACTOR = UUID.fromString("2f6c1e0a-3b7d-4c55-8f1e-6a0b9d7c1234");

    private final FineRepository fines = mock(FineRepository.class);
    private final FineActionRepository actions = mock(FineActionRepository.class);
    private final PlateOwnerLookup owners = mock(PlateOwnerLookup.class);
    private final ZoneDirectory zones = mock(ZoneDirectory.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private FineService service;

    @BeforeEach
    void setUp() {
        service = new FineService(fines, actions, owners, zones, events, TestFixtures.properties(), Clock.fixed(NOW, ZoneOffset.UTC));
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
        assertThat(event.violationType()).isEqualTo(ViolationType.WRONG_PERMIT);
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
    void getUnknownFineIsNotFound() {
        UUID id = UUID.randomUUID();
        when(fines.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void byPlateLeavesOutVoidedFinesUnlessAskedTo() {
        Pageable page = PageRequest.of(1, 50);
        Page<Fine> visible = new PageImpl<>(List.of());
        Page<Fine> everything = new PageImpl<>(List.of());
        when(fines.findByPlateAndVoidedAtIsNull("ABC123", page)).thenReturn(visible);
        when(fines.findByPlate("ABC123", page)).thenReturn(everything);

        assertThat(service.byPlate("ABC123", false, page)).isSameAs(visible);
        assertThat(service.byPlate("ABC123", true, page)).isSameAs(everything);
    }

    @Test
    void byZoneFiltersOnPaidOnlyWhenAskedToAndLeavesOutVoidedFinesUnlessAskedTo() {
        Pageable page = PageRequest.of(0, 100);
        Page<Fine> all = new PageImpl<>(List.of());
        Page<Fine> allVisible = new PageImpl<>(List.of());
        Page<Fine> paid = new PageImpl<>(List.of());
        Page<Fine> paidVisible = new PageImpl<>(List.of());
        Page<Fine> unpaid = new PageImpl<>(List.of());
        Page<Fine> unpaidVisible = new PageImpl<>(List.of());
        when(fines.findByZoneId("z1", page)).thenReturn(all);
        when(fines.findByZoneIdAndVoidedAtIsNull("z1", page)).thenReturn(allVisible);
        when(fines.findByZoneIdAndPaid("z1", true, page)).thenReturn(paid);
        when(fines.findByZoneIdAndPaidAndVoidedAtIsNull("z1", true, page)).thenReturn(paidVisible);
        when(fines.findByZoneIdAndPaid("z1", false, page)).thenReturn(unpaid);
        when(fines.findByZoneIdAndPaidAndVoidedAtIsNull("z1", false, page)).thenReturn(unpaidVisible);

        assertThat(service.byZone("z1", null, true, page)).isSameAs(all);
        assertThat(service.byZone("z1", null, false, page)).isSameAs(allVisible);
        assertThat(service.byZone("z1", true, true, page)).isSameAs(paid);
        assertThat(service.byZone("z1", true, false, page)).isSameAs(paidVisible);
        assertThat(service.byZone("z1", false, true, page)).isSameAs(unpaid);
        assertThat(service.byZone("z1", false, false, page)).isSameAs(unpaidVisible);
    }

    @Test
    void historyDelegatesToTheRepositoryWithTheGivenPage() {
        UUID id = UUID.randomUUID();
        Pageable page = PageRequest.of(0, 10);
        Page<FineAction> result = new PageImpl<>(List.of());
        when(actions.findByFineId(id, page)).thenReturn(result);

        assertThat(service.history(id, page)).isSameAs(result);
    }

    // ---- helpers ----

    /** An issued, unpaid fine of 700 SEK that the repository hands out (and locks) by id; issuing events are forgotten. */
    private Fine unpaidFine() {
        Fine fine = service.issueFine(TestFixtures.violation(ViolationType.WRONG_PERMIT, NOW.minusSeconds(900), NOW.minusSeconds(60)));
        when(fines.findByIdForUpdate(fine.getId())).thenReturn(Optional.of(fine));
        clearInvocations(events);
        return fine;
    }

    private FineAction savedAction() {
        ArgumentCaptor<FineAction> captor = ArgumentCaptor.forClass(FineAction.class);
        verify(actions).save(captor.capture());
        return captor.getValue();
    }

    private Object publishedPayload(String routingKey) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq(RabbitConfig.VIOLATION_EXCHANGE), eq(routingKey), payload.capture());
        return payload.getValue();
    }

    // ---- void ----

    @Test
    void voidingMarksTheFineWritesTheAuditRowAndPublishesFineVoided() {
        Fine fine = unpaidFine();

        Fine result = service.voidFine(fine.getId(), "Plate misread", ACTOR);

        assertThat(result).isSameAs(fine);
        assertThat(fine.isVoided()).isTrue();
        assertThat(fine.getVoidedAt()).isEqualTo(NOW);
        assertThat(fine.getVoidReason()).isEqualTo("Plate misread");
        assertThat(fine.getVoidedBy()).isEqualTo(ACTOR);
        assertThat(fine.isPaid()).isFalse();

        FineAction entry = savedAction();
        assertThat(entry.getFineId()).isEqualTo(fine.getId());
        assertThat(entry.getAction()).isEqualTo("void");
        assertThat(entry.getActorId()).isEqualTo(ACTOR);
        assertThat(entry.getReason()).isEqualTo("Plate misread");
        assertThat(entry.getAt()).isEqualTo(NOW);
        assertThat(entry.getDetails()).isEmpty();

        FineVoidedEvent event = (FineVoidedEvent) publishedPayload("fine.voided");
        assertThat(event).isEqualTo(new FineVoidedEvent(fine.getId(), TestFixtures.PLATE, TestFixtures.ZONE, NOW));
    }

    @Test
    void voidingAnAlreadyVoidedFineChangesNothingAndSaysNothing() {
        Fine fine = unpaidFine();
        service.voidFine(fine.getId(), "Plate misread", ACTOR);
        clearInvocations(events, actions);

        Fine again = service.voidFine(fine.getId(), "A different reason", UUID.randomUUID());

        assertThat(again).isSameAs(fine);
        assertThat(fine.getVoidReason()).isEqualTo("Plate misread");
        assertThat(fine.getVoidedBy()).isEqualTo(ACTOR);
        assertThat(fine.getVoidedAt()).isEqualTo(NOW);
        verifyNoInteractions(events, actions);
    }

    @Test
    void aPaidFineCannotBeVoided() {
        Fine fine = unpaidFine();
        service.markPaidManually(fine.getId(), "Paid at the counter", PaidMethod.CASH, ACTOR);
        clearInvocations(events, actions);

        assertThatThrownBy(() -> service.voidFine(fine.getId(), "Plate misread", ACTOR)).isInstanceOf(ConflictException.class);

        assertThat(fine.isVoided()).isFalse();
        verifyNoInteractions(events, actions);
    }

    @Test
    void commandsOnAnUnknownFineAreNotFound() {
        UUID id = UUID.randomUUID();
        when(fines.findByIdForUpdate(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.voidFine(id, "Plate misread", ACTOR)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.markPaidManually(id, "Paid", PaidMethod.CASH, ACTOR))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- manual payment ----

    @ParameterizedTest
    @CsvSource({"CASH,cash", "TRANSFER,transfer", "OTHER,other"})
    void markingAsPaidRecordsMethodAndActorWritesTheAuditRowAndPublishesFinePaid(PaidMethod method, String wire) {
        Fine fine = unpaidFine();

        Fine result = service.markPaidManually(fine.getId(), "Paid at the counter", method, ACTOR);

        assertThat(result).isSameAs(fine);
        assertThat(fine.isPaid()).isTrue();
        assertThat(fine.getPaidAt()).isEqualTo(NOW);
        assertThat(fine.getPaidMethod()).isEqualTo(wire);
        assertThat(fine.getPaidBy()).isEqualTo(ACTOR);
        assertThat(fine.isVoided()).isFalse();

        FineAction entry = savedAction();
        assertThat(entry.getAction()).isEqualTo("mark_paid");
        assertThat(entry.getActorId()).isEqualTo(ACTOR);
        assertThat(entry.getReason()).isEqualTo("Paid at the counter");
        assertThat(entry.getDetails()).isEqualTo(Map.of("method", wire));

        FinePaidEvent event = (FinePaidEvent) publishedPayload("fine.paid");
        assertThat(event.fineId()).isEqualTo(fine.getId());
        assertThat(event.plate()).isEqualTo(TestFixtures.PLATE);
        assertThat(event.zoneId()).isEqualTo(TestFixtures.ZONE);
        assertThat(event.amountSek()).isEqualByComparingTo("700");
        assertThat(event.method()).isEqualTo(wire);
        assertThat(event.occurredAt()).isEqualTo(NOW);
    }

    @Test
    void markingAnAlreadyPaidFineAsPaidChangesNothingAndSaysNothing() {
        Fine fine = unpaidFine();
        service.markPaidManually(fine.getId(), "Paid at the counter", PaidMethod.CASH, ACTOR);
        clearInvocations(events, actions);

        Fine again = service.markPaidManually(fine.getId(), "Paid again", PaidMethod.TRANSFER, UUID.randomUUID());

        assertThat(again).isSameAs(fine);
        assertThat(fine.getPaidMethod()).isEqualTo("cash");
        assertThat(fine.getPaidBy()).isEqualTo(ACTOR);
        verifyNoInteractions(events, actions);
    }

    @Test
    void aVoidedFineCannotBeMarkedAsPaid() {
        Fine fine = unpaidFine();
        service.voidFine(fine.getId(), "Plate misread", ACTOR);
        clearInvocations(events, actions);

        assertThatThrownBy(() -> service.markPaidManually(fine.getId(), "Paid at the counter", PaidMethod.CASH, ACTOR))
                .isInstanceOf(ConflictException.class);

        assertThat(fine.isPaid()).isFalse();
        verifyNoInteractions(events, actions);
    }

    @Test
    void onlineIsNotAManualMethod() {
        Fine fine = unpaidFine();

        assertThatThrownBy(() -> service.markPaidManually(fine.getId(), "Paid", PaidMethod.ONLINE, ACTOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(fine.isPaid()).isFalse();
    }

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
        verify(events, times(1)).publish(eq(RabbitConfig.VIOLATION_EXCHANGE), eq("fine.paid"), any());
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
        service.markPaidManually(fine.getId(), "Paid at the counter", PaidMethod.CASH, ACTOR);
        clearInvocations(events, actions);

        service.markPaidOnline(fine.getId(), "pay-1");

        assertThat(fine.getPaidMethod()).isEqualTo("cash");
        verifyNoInteractions(events, actions);
    }

    @Test
    void anOnlinePaymentForAVoidedFineLeavesItVoidedAndSaysNothing() {
        Fine fine = unpaidFine();
        service.voidFine(fine.getId(), "Plate misread", ACTOR);
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

    // ---- logging ----

    @Test
    void theFreeTextReasonIsNeverLogged() {
        Logger logger = (Logger) LoggerFactory.getLogger(FineService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level previous = logger.getLevel();
        logger.setLevel(Level.ALL);
        try {
            Fine toVoid = unpaidFine();
            service.voidFine(toVoid.getId(), "secret-void-reason", ACTOR);
            Fine toPay = unpaidFine();
            service.markPaidManually(toPay.getId(), "secret-paid-reason", PaidMethod.CASH, ACTOR);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }

        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(m -> m.contains("secret-void-reason") || m.contains("secret-paid-reason"));
    }
}
