package com.parkview.ruleengine.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.FineAction;
import com.parkview.ruleengine.domain.PaidMethod;
import com.parkview.ruleengine.dto.event.FinePaidEvent;
import com.parkview.ruleengine.dto.event.FineVoidedEvent;
import com.parkview.ruleengine.web.ConflictException;
import com.parkview.ruleengine.web.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FineManagementServiceTest extends FineServiceTestBase {

    private final FineManagementService service = new FineManagementService(fines, actions, disputes, closer, fineEvents, clock);

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
    // ---- logging ----

    @Test
    void theFreeTextReasonIsNeverLogged() {
        Logger logger = (Logger) LoggerFactory.getLogger(FineManagementService.class);
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
