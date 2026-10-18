package com.parkview.ruleengine.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.parkview.ruleengine.domain.Dispute;
import com.parkview.ruleengine.domain.DisputeChannel;
import com.parkview.ruleengine.domain.DisputeStatus;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.FineAction;
import com.parkview.ruleengine.domain.PaidMethod;
import com.parkview.ruleengine.dto.event.FineDisputeDecidedEvent;
import com.parkview.ruleengine.dto.event.FineDisputedEvent;
import com.parkview.ruleengine.dto.event.FineVoidedEvent;
import com.parkview.ruleengine.web.ConflictException;
import com.parkview.ruleengine.web.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The dispute commands on the state of the fine: rules, audit rows, events, the reuse of the void logic, the payment hold. */
class DisputeServiceTest extends FineServiceTestBase {

    private static final UUID DRIVER = UUID.fromString("7d0c2a56-6b53-4d4e-9a0c-0f9d1f4f4e11");
    private static final String MESSAGE = "The car was loading, I was gone in five minutes";
    private static final String NOTE = "The delivery note proves the stop";

    private final Map<UUID, Dispute> store = new HashMap<>();
    private final FineManagementService management = new FineManagementService(fines, actions, disputes, closer, fineEvents, clock);
    private final DisputeService service = new DisputeService(fines, disputes, actions, management, closer, fineEvents, clock);

    /** Behaves like the repository for the few queries the commands use. */
    @BeforeEach
    void fakeDisputeRepository() {
        when(disputes.saveAndFlush(any(Dispute.class))).thenAnswer(inv -> {
            Dispute d = inv.getArgument(0);
            ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
            store.put(d.getId(), d);
            return d;
        });
        when(disputes.findById(any(UUID.class))).thenAnswer(inv -> Optional.ofNullable(store.get(inv.<UUID>getArgument(0))));
        when(disputes.findFineIdById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.<UUID>getArgument(0))).map(Dispute::getFineId));
        when(disputes.existsByFineIdAndStatus(any(UUID.class), any(DisputeStatus.class))).thenAnswer(inv -> store.values().stream()
                .anyMatch(d -> d.getFineId().equals(inv.getArgument(0)) && d.getStatus() == inv.getArgument(1)));
        when(disputes.findByFineIdAndStatus(any(UUID.class), any(DisputeStatus.class))).thenAnswer(inv -> store.values().stream()
                .filter(d -> d.getFineId().equals(inv.getArgument(0)) && d.getStatus() == inv.getArgument(1)).findFirst());
    }

    private Dispute openDispute(Fine fine) {
        Dispute dispute = service.open(fine.getId(), DisputeChannel.DRIVER, DRIVER, MESSAGE);
        clearInvocations(events, actions);
        return dispute;
    }

    // ---- open ----

    @Test
    void openingStoresTheDisputeWritesTheAuditRowAndAnnouncesIt() {
        Fine fine = unpaidFine();

        Dispute dispute = service.open(fine.getId(), DisputeChannel.DRIVER, DRIVER, MESSAGE);

        assertThat(dispute.getStatus()).isEqualTo(DisputeStatus.OPEN);
        assertThat(dispute.getFineId()).isEqualTo(fine.getId());
        assertThat(dispute.getPlate()).isEqualTo(TestFixtures.PLATE);
        assertThat(dispute.getZoneId()).isEqualTo(TestFixtures.ZONE);
        assertThat(dispute.getChannel()).isEqualTo(DisputeChannel.DRIVER);
        assertThat(dispute.getOpenedByUserId()).isEqualTo(DRIVER);
        assertThat(dispute.getMessage()).isEqualTo(MESSAGE);
        assertThat(dispute.getOpenedAt()).isEqualTo(NOW);
        assertThat(dispute.getDecidedAt()).isNull();

        FineAction entry = savedAction();
        assertThat(entry.getAction()).isEqualTo("dispute_opened");
        assertThat(entry.getActorId()).isEqualTo(DRIVER);
        assertThat(entry.getReason()).isEqualTo(MESSAGE);
        assertThat(entry.getDetails()).isEqualTo(Map.of("disputeId", dispute.getId().toString(), "channel", "driver"));

        FineDisputedEvent event = (FineDisputedEvent) publishedPayload("fine.disputed");
        assertThat(event).isEqualTo(new FineDisputedEvent(dispute.getId(), fine.getId(), TestFixtures.PLATE, TestFixtures.ZONE, "driver", NOW));
    }

    @Test
    void anOperatorOnThePhoneOpensItOnTheOperatorChannel() {
        Fine fine = unpaidFine();

        Dispute dispute = service.open(fine.getId(), DisputeChannel.OPERATOR, ACTOR, MESSAGE);

        assertThat(dispute.getChannel()).isEqualTo(DisputeChannel.OPERATOR);
        assertThat(dispute.getOpenedByUserId()).isEqualTo(ACTOR);
        assertThat(((FineDisputedEvent) publishedPayload("fine.disputed")).channel()).isEqualTo("operator");
    }

    @Test
    void aPaidFineCannotBeDisputed() {
        Fine fine = unpaidFine();
        management.markPaidManually(fine.getId(), "Paid at the counter", PaidMethod.CASH, ACTOR);
        clearInvocations(events, actions);

        assertThatThrownBy(() -> service.open(fine.getId(), DisputeChannel.DRIVER, DRIVER, MESSAGE))
                .isInstanceOf(ConflictException.class).hasMessage("This fine has already been paid, so it can no longer be disputed.");

        assertThat(store).isEmpty();
        verifyNoInteractions(events, actions);
    }

    @Test
    void aVoidedFineCannotBeDisputed() {
        Fine fine = unpaidFine();
        management.voidFine(fine.getId(), "Plate misread", ACTOR);
        clearInvocations(events, actions);

        assertThatThrownBy(() -> service.open(fine.getId(), DisputeChannel.DRIVER, DRIVER, MESSAGE))
                .isInstanceOf(ConflictException.class).hasMessage("This fine has been cancelled, so there is nothing to dispute.");

        verifyNoInteractions(events, actions);
    }

    @Test
    void aSecondOpenDisputeIsAConflict() {
        Fine fine = unpaidFine();
        openDispute(fine);

        assertThatThrownBy(() -> service.open(fine.getId(), DisputeChannel.OPERATOR, ACTOR, MESSAGE))
                .isInstanceOf(ConflictException.class)
                .hasMessage("A review of this fine has already been requested and is still open.");

        assertThat(store).hasSize(1);
        verifyNoInteractions(events, actions);
    }

    @Test
    void aWithdrawnDisputeMayBeOpenedAgainButAnUpheldOneMayNot() {
        Fine fine = unpaidFine();
        Dispute first = openDispute(fine);
        service.withdraw(first.getId(), DRIVER);

        Dispute second = service.open(fine.getId(), DisputeChannel.DRIVER, DRIVER, MESSAGE);
        service.decide(second.getId(), DisputeStatus.UPHELD, ACTOR, NOTE);

        assertThatThrownBy(() -> service.open(fine.getId(), DisputeChannel.DRIVER, DRIVER, MESSAGE))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This fine has already been reviewed by an operator and the fine stands.");
    }

    @Test
    void disputingAnUnknownFineIsNotFound() {
        UUID id = UUID.randomUUID();
        when(fines.findByIdForUpdate(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.open(id, DisputeChannel.DRIVER, DRIVER, MESSAGE)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- the payment hold ----

    @Test
    void anOpenDisputeStopsTheOperatorFromMarkingTheFineAsPaid() {
        Fine fine = unpaidFine();
        openDispute(fine);

        assertThatThrownBy(() -> management.markPaidManually(fine.getId(), "Paid at the counter", PaidMethod.CASH, ACTOR))
                .isInstanceOf(ConflictException.class);

        assertThat(fine.isPaid()).isFalse();
        verifyNoInteractions(events, actions);
    }

    @Test
    void theHoldEndsWhenTheDisputeIsUpheld() {
        Fine fine = unpaidFine();
        Dispute dispute = openDispute(fine);
        service.decide(dispute.getId(), DisputeStatus.UPHELD, ACTOR, NOTE);

        management.markPaidManually(fine.getId(), "Paid at the counter", PaidMethod.CASH, ACTOR);

        assertThat(fine.isPaid()).isTrue();
    }

    // ---- withdraw ----

    @Test
    void withdrawingClosesTheDisputeAndRecordsWhoDidIt() {
        Fine fine = unpaidFine();
        Dispute dispute = openDispute(fine);

        Dispute result = service.withdraw(dispute.getId(), DRIVER);

        assertThat(result.getStatus()).isEqualTo(DisputeStatus.WITHDRAWN);
        assertThat(result.getDecidedAt()).isEqualTo(NOW);
        assertThat(result.getDecidedByUserId()).isEqualTo(DRIVER);
        assertThat(result.getDecisionNote()).isNull();
        assertThat(fine.isVoided()).isFalse();

        FineAction entry = savedAction();
        assertThat(entry.getAction()).isEqualTo("dispute_withdrawn");
        assertThat(entry.getActorId()).isEqualTo(DRIVER);
        assertThat(entry.getReason()).isNull();
        FineDisputeDecidedEvent event = (FineDisputeDecidedEvent) publishedPayload("fine.dispute.decided");
        assertThat(event.outcome()).isEqualTo("withdrawn");
        assertThat(event.disputeId()).isEqualTo(dispute.getId());
        assertThat(event.decisionNote()).isNull();                                // a withdrawal has no motivation
    }

    @Test
    void aWithdrawnDisputeIsFinal() {
        Fine fine = unpaidFine();
        Dispute dispute = openDispute(fine);
        service.withdraw(dispute.getId(), DRIVER);
        clearInvocations(events, actions);

        assertThatThrownBy(() -> service.withdraw(dispute.getId(), DRIVER))
                .isInstanceOf(ConflictException.class).hasMessage("This dispute has been withdrawn and cannot be changed.");
        assertThatThrownBy(() -> service.decide(dispute.getId(), DisputeStatus.VOIDED, ACTOR, NOTE))
                .isInstanceOf(ConflictException.class).hasMessage("This dispute has been withdrawn and cannot be changed.");

        assertThat(fine.isVoided()).isFalse();
        verifyNoInteractions(events, actions);
    }

    @Test
    void anUnknownDisputeIsNotFound() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.withdraw(id, DRIVER)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.decide(id, DisputeStatus.UPHELD, ACTOR, NOTE)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- decision ----

    @Test
    void upholdingClosesTheDisputeWithTheNoteAndLeavesTheFineAlone() {
        Fine fine = unpaidFine();
        Dispute dispute = openDispute(fine);

        Dispute result = service.decide(dispute.getId(), DisputeStatus.UPHELD, ACTOR, NOTE);

        assertThat(result.getStatus()).isEqualTo(DisputeStatus.UPHELD);
        assertThat(result.getDecidedByUserId()).isEqualTo(ACTOR);
        assertThat(result.getDecidedAt()).isEqualTo(NOW);
        assertThat(result.getDecisionNote()).isEqualTo(NOTE);
        assertThat(fine.isVoided()).isFalse();
        assertThat(fine.isPaid()).isFalse();

        FineAction entry = savedAction();
        assertThat(entry.getAction()).isEqualTo("dispute_upheld");
        assertThat(entry.getActorId()).isEqualTo(ACTOR);
        assertThat(entry.getReason()).isEqualTo(NOTE);
        assertThat(((FineDisputeDecidedEvent) publishedPayload("fine.dispute.decided")).outcome()).isEqualTo("upheld");
        verify(events, never()).enqueue(any(), any(), any(), eq("fine.voided"), any());
    }

    @Test
    void voidingTheFineGoesThroughTheExistingVoidLogicWithTheNoteAsReasonAndAuditsBoth() {
        Fine fine = unpaidFine();
        Dispute dispute = openDispute(fine);

        Dispute result = service.decide(dispute.getId(), DisputeStatus.VOIDED, ACTOR, NOTE);

        assertThat(result.getStatus()).isEqualTo(DisputeStatus.VOIDED);
        assertThat(result.getDecidedByUserId()).isEqualTo(ACTOR);
        assertThat(result.getDecisionNote()).isEqualTo(NOTE);
        assertThat(fine.isVoided()).isTrue();
        assertThat(fine.getVoidReason()).isEqualTo(NOTE);
        assertThat(fine.getVoidedBy()).isEqualTo(ACTOR);

        ArgumentCaptor<FineAction> saved = ArgumentCaptor.forClass(FineAction.class);
        verify(actions, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(FineAction::getAction).containsExactly("void", "dispute_voided");
        assertThat(saved.getAllValues()).extracting(FineAction::getReason).containsExactly(NOTE, NOTE);
        assertThat(saved.getAllValues()).extracting(FineAction::getActorId).containsExactly(ACTOR, ACTOR);

        assertThat(publishedPayload("fine.voided")).isEqualTo(new FineVoidedEvent(fine.getId(), TestFixtures.PLATE, TestFixtures.ZONE, NOW));
        FineDisputeDecidedEvent decided = (FineDisputeDecidedEvent) publishedPayload("fine.dispute.decided");
        assertThat(decided.outcome()).isEqualTo("voided");
        assertThat(decided.decisionNote()).isEqualTo(NOTE);                       // the driver reads the motivation in the notification
    }

    @Test
    void decidingTwiceIsAConflictAndChangesNothing() {
        Fine fine = unpaidFine();
        Dispute dispute = openDispute(fine);
        service.decide(dispute.getId(), DisputeStatus.UPHELD, ACTOR, NOTE);
        clearInvocations(events, actions);

        assertThatThrownBy(() -> service.decide(dispute.getId(), DisputeStatus.VOIDED, ACTOR, NOTE))
                .isInstanceOf(ConflictException.class).hasMessage("This dispute has already been decided and cannot be changed.");

        assertThat(fine.isVoided()).isFalse();
        assertThat(dispute.getStatus()).isEqualTo(DisputeStatus.UPHELD);
        verifyNoInteractions(events, actions);
    }

    @Test
    void anOperatorDecidesUpheldOrVoidedNothingElse() {
        Fine fine = unpaidFine();
        Dispute dispute = openDispute(fine);

        assertThatThrownBy(() -> service.decide(dispute.getId(), DisputeStatus.WITHDRAWN, ACTOR, NOTE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.decide(dispute.getId(), DisputeStatus.OPEN, ACTOR, NOTE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(dispute.isOpen()).isTrue();
    }

    // ---- the other ways an open dispute ends ----

    @Test
    void anOperatorVoidingTheFineDirectlyEndsTheOpenDisputeAsVoided() {
        Fine fine = unpaidFine();
        Dispute dispute = openDispute(fine);

        management.voidFine(fine.getId(), "Plate misread", ACTOR);

        assertThat(dispute.getStatus()).isEqualTo(DisputeStatus.VOIDED);
        assertThat(dispute.getDecisionNote()).isEqualTo("Plate misread");
        assertThat(((FineDisputeDecidedEvent) publishedPayload("fine.dispute.decided")).outcome()).isEqualTo("voided");
    }

    @Test
    void aPaymentThatCompletesWhileTheDisputeIsOpenKeepsTheFinePaidAndTheSystemUpholdsTheDispute() {
        Fine fine = unpaidFine();
        Dispute dispute = openDispute(fine);
        FineSettlementService settlement = new FineSettlementService(fines, actions, closer, fineEvents, clock);

        settlement.markPaidOnline(fine.getId(), "pay-1");

        assertThat(fine.isPaid()).isTrue();
        assertThat(dispute.getStatus()).isEqualTo(DisputeStatus.UPHELD);
        assertThat(dispute.getDecidedByUserId()).isNull();
        assertThat(dispute.getDecisionNote()).isEqualTo(FineSettlementService.PAID_WHILE_DISPUTED_NOTE);

        ArgumentCaptor<FineAction> saved = ArgumentCaptor.forClass(FineAction.class);
        verify(actions, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(FineAction::getAction).containsExactly("paid_online", "dispute_upheld");
        assertThat(saved.getAllValues().get(1).getActorId()).isNull();
        assertThat(((FineDisputeDecidedEvent) publishedPayload("fine.dispute.decided")).outcome()).isEqualTo("upheld");
    }

    @Test
    void theDecidedEventCarriesTheOwnerOfThePlateWhenKnown() {
        UUID owner = UUID.randomUUID();
        Fine fine = unpaidFine();
        when(owners.ownerOf(TestFixtures.PLATE)).thenReturn(Optional.of(owner));
        Dispute dispute = openDispute(fine);

        service.decide(dispute.getId(), DisputeStatus.UPHELD, ACTOR, NOTE);

        assertThat(((FineDisputeDecidedEvent) publishedPayload("fine.dispute.decided")).userId()).isEqualTo(owner);
    }

    @Test
    void anUnknownOwnerIsNullInTheDecidedEvent() {
        Fine fine = unpaidFine();
        Dispute dispute = openDispute(fine);

        service.decide(dispute.getId(), DisputeStatus.UPHELD, ACTOR, NOTE);

        assertThat(((FineDisputeDecidedEvent) publishedPayload("fine.dispute.decided")).userId()).isNull();
        verify(events, never()).enqueue(any(), any(), any(), eq("fine.voided"), any());
    }

    // ---- logging ----

    @Test
    void theMessageAndTheNoteAreNeverLogged() {
        Logger[] loggers = {(Logger) LoggerFactory.getLogger(DisputeService.class), (Logger) LoggerFactory.getLogger(DisputeCloser.class),
                (Logger) LoggerFactory.getLogger(FineManagementService.class)};
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        for (Logger logger : loggers) {
            logger.addAppender(appender);
            logger.setLevel(Level.ALL);
        }
        try {
            Dispute dispute = service.open(unpaidFine().getId(), DisputeChannel.DRIVER, DRIVER, "secret-dispute-message");
            service.decide(dispute.getId(), DisputeStatus.VOIDED, ACTOR, "secret-decision-note");
            Dispute second = service.open(unpaidFine().getId(), DisputeChannel.DRIVER, DRIVER, "secret-dispute-message-2");
            service.withdraw(second.getId(), DRIVER);
        } finally {
            for (Logger logger : loggers) {
                logger.detachAppender(appender);
                logger.setLevel(null);
            }
        }

        assertThat(appender.list).isNotEmpty();
        List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).noneMatch(m -> m.contains("secret-"));
    }
}
