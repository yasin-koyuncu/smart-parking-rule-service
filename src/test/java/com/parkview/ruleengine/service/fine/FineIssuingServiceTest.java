package com.parkview.ruleengine.service.fine;

import com.parkview.ruleengine.config.RabbitConfig;
import com.parkview.ruleengine.config.RuleEngineProperties;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
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

        Fine fine = service.issueFine(violation).orElseThrow();

        assertThat(fine.getAmount()).isEqualByComparingTo(amount);
        assertThat(fine.getAmount().scale()).isEqualTo(2);
        assertThat(fine.getCurrency()).isEqualTo("SEK");
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

        Fine fine = service.issueFine(violation).orElseThrow();

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
        assertThat(event.amount()).isEqualByComparingTo(BigDecimal.valueOf(700));
        assertThat(event.currency()).isEqualTo("SEK");
        assertThat(event.timestamp()).isEqualTo(NOW.toEpochMilli());
        assertThat(event.violationType()).isEqualTo(ViolationType.WRONG_PERMIT);
        assertThat(event.sessionId()).isNull();
        assertThat(fine.getSessionId()).isNull();
    }

    @Test
    void theSessionOfTheViolationIsCarriedToTheFineAndItsEvent() {
        UUID sessionId = UUID.randomUUID();
        Violation violation = TestFixtures.violation(ViolationType.WRONG_PERMIT, NOW.minusSeconds(900), NOW.minusSeconds(60));
        ReflectionTestUtils.setField(violation, "sessionId", sessionId);

        Fine fine = service.issueFine(violation).orElseThrow();

        assertThat(fine.getSessionId()).isEqualTo(sessionId);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).enqueue(eq(OutboxService.AGGREGATE_FINE), eq(fine.getId()), eq(RabbitConfig.VIOLATION_EXCHANGE), eq("fine.issued"),
                payload.capture());
        assertThat(((FineIssuedEvent) payload.getValue()).sessionId()).isEqualTo(sessionId);
    }

    @Test
    void theEvidenceIsFrozenForTheFineBeforeItsEventIsQueued() {
        Violation violation = TestFixtures.violation(ViolationType.OVERSTAY, NOW.minusSeconds(900), NOW.minusSeconds(60));

        Fine fine = service.issueFine(violation).orElseThrow();

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

        assertThat(service.issueFine(violation).orElseThrow().getUserId()).isEqualTo(owner);
        verifyNoInteractions(owners);
    }

    // ---- the currency of the site ----

    private FineIssuingService issuingWith(Map<String, Map<ViolationType, BigDecimal>> fineAmounts) {
        RuleEngineProperties properties = TestFixtures.properties(fineAmounts);
        return new FineIssuingService(fines, owners, sites, metrics, fineEvents, evidence, properties, clock);
    }

    @Test
    void theFineTakesTheCurrencyOfItsSiteAndTheAmountConfiguredForIt() {
        when(sites.currencyOf(TestFixtures.ZONE)).thenReturn(Optional.of("NOK"));
        FineIssuingService norwegian = issuingWith(Map.of("NOK", Map.of(ViolationType.OVERSTAY, new BigDecimal("1100"))));
        Violation violation = TestFixtures.violation(ViolationType.OVERSTAY, NOW.minusSeconds(900), NOW.minusSeconds(60));

        Fine fine = norwegian.issueFine(violation).orElseThrow();

        assertThat(fine.getCurrency()).isEqualTo("NOK");
        assertThat(fine.getAmount()).isEqualByComparingTo("1100.00");
        FineIssuedEvent event = (FineIssuedEvent) publishedPayload("fine.issued");
        assertThat(event.currency()).isEqualTo("NOK");
        assertThat(event.amount()).isEqualByComparingTo("1100");
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void aCurrencyWithoutConfiguredAmountsGetsNoFineTheViolationStaysOpenAndTheErrorIsCounted() {
        when(sites.currencyOf(TestFixtures.ZONE)).thenReturn(Optional.of("USD"));
        Violation violation = TestFixtures.violation(ViolationType.OVERSTAY, NOW.minusSeconds(900), NOW.minusSeconds(60));

        Optional<Fine> fine = service.issueFine(violation);

        assertThat(fine).isEmpty();
        assertThat(violation.isActive()).isTrue();
        assertThat(violation.getFineId()).isNull();
        // deferring the retry is the job of the sweep (ViolationJobs), the issuing service only reports "not issued"
        assertThat(violation.getFiningRetryAt()).isNull();
        verify(fines, never()).save(any(Fine.class));
        verifyNoInteractions(events, evidence);
        assertThat(registry.counter("ruleengine.fines.not_issued", "cause", "no_fine_amount", "currency", "USD", "type", "OVERSTAY").count())
                .isEqualTo(1.0);
    }

    @Test
    void aViolationTypeWithoutAnAmountInAConfiguredCurrencyGetsNoFine() {
        when(sites.currencyOf(TestFixtures.ZONE)).thenReturn(Optional.of("NOK"));
        FineIssuingService norwegian = issuingWith(Map.of("NOK", Map.of(ViolationType.OVERSTAY, new BigDecimal("1100"))));
        Violation violation = TestFixtures.violation(ViolationType.NO_PARKING, NOW.minusSeconds(900), NOW.minusSeconds(60));

        assertThat(norwegian.issueFine(violation)).isEmpty();

        verify(fines, never()).save(any(Fine.class));
        assertThat(registry.counter("ruleengine.fines.not_issued", "cause", "no_fine_amount", "currency", "NOK", "type", "NO_PARKING").count())
                .isEqualTo(1.0);
    }

    @Test
    void aSiteWithoutAValidCurrencyGetsNoFineAndThereIsNoDefaultCurrency() {
        when(sites.currencyOf(TestFixtures.ZONE)).thenReturn(Optional.empty());
        Violation violation = TestFixtures.violation(ViolationType.WRONG_PERMIT, NOW.minusSeconds(900), NOW.minusSeconds(60));

        assertThat(service.issueFine(violation)).isEmpty();

        assertThat(violation.isActive()).isTrue();
        verify(fines, never()).save(any(Fine.class));
        verifyNoInteractions(events, evidence);
        assertThat(registry.counter("ruleengine.fines.not_issued", "cause", "no_site_currency", "currency", "unknown", "type", "WRONG_PERMIT")
                .count()).isEqualTo(1.0);
    }
}
