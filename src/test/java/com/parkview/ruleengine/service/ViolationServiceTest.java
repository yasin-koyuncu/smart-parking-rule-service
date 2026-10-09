package com.parkview.ruleengine.service;

import com.parkview.ruleengine.client.PlateOwnerLookup;
import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.config.RabbitConfig;
import com.parkview.ruleengine.domain.ParkingSpot;
import com.parkview.ruleengine.domain.ResolutionReason;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.ViolationType;
import com.parkview.ruleengine.dto.event.ViolationCreatedEvent;
import com.parkview.ruleengine.dto.event.ViolationResolvedEvent;
import com.parkview.ruleengine.messaging.EventPublisher;
import com.parkview.ruleengine.repository.ViolationRepository;
import com.parkview.ruleengine.web.ConflictException;
import com.parkview.ruleengine.web.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.parkview.ruleengine.service.TestFixtures.PLATE;
import static com.parkview.ruleengine.service.TestFixtures.ZONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ViolationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    private final ViolationRepository violations = mock(ViolationRepository.class);
    private final PlateOwnerLookup owners = mock(PlateOwnerLookup.class);
    private final ZoneDirectory zones = mock(ZoneDirectory.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private ViolationService service;

    @BeforeEach
    void setUp() {
        service = new ViolationService(violations, owners, zones, events, TestFixtures.properties(),
                Clock.fixed(NOW, ZoneOffset.UTC), TestTransactions.template());
        when(owners.ownerOf(anyString())).thenReturn(Optional.empty());
        when(zones.addressOf(anyString())).thenReturn(Optional.empty());
        when(violations.saveAndFlush(any(Violation.class))).thenAnswer(inv -> {
            Violation v = inv.getArgument(0);
            ReflectionTestUtils.setField(v, "id", UUID.randomUUID());
            return v;
        });
        when(violations.findRecentlyFinedIds(anyString(), any(), any(), any(), any(Pageable.class))).thenReturn(List.of());
    }

    @Test
    void opensAViolationWithTheSpotsGraceAndPublishesCreated() {
        ParkingSpot spot = TestFixtures.spot("permit", "resident", 0, 45);
        UUID owner = UUID.randomUUID();
        when(owners.ownerOf(PLATE)).thenReturn(Optional.of(owner));
        when(zones.addressOf(ZONE)).thenReturn(Optional.of("Storgatan 1"));
        when(violations.findActive(PLATE, spot.id(), ViolationType.WRONG_PERMIT)).thenReturn(Optional.empty());

        service.confirm(spot, ViolationType.WRONG_PERMIT, PLATE, "cam-1");

        ArgumentCaptor<Violation> saved = ArgumentCaptor.forClass(Violation.class);
        verify(violations).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getGraceUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(45)));
        assertThat(saved.getValue().getLastSeenAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getUserId()).isEqualTo(owner);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq(RabbitConfig.VIOLATION_EXCHANGE), eq("violation.created"), payload.capture());
        ViolationCreatedEvent event = (ViolationCreatedEvent) payload.getValue();
        assertThat(event.plate()).isEqualTo(PLATE);
        assertThat(event.zoneId()).isEqualTo(ZONE);
        assertThat(event.zoneAddress()).isEqualTo("Storgatan 1");
        assertThat(event.cameraId()).isEqualTo("cam-1");
        assertThat(event.userId()).isEqualTo(owner);
        assertThat(event.spotId()).isEqualTo(spot.id());
        assertThat(event.spotNumber()).isEqualTo("A1");
        assertThat(event.violationType()).isEqualTo(ViolationType.WRONG_PERMIT);
        assertThat(event.graceMinutes()).isEqualTo(45);
        assertThat(event.timestamp()).isEqualTo(NOW.toEpochMilli());
    }

    @Test
    void graceFallsBackToTheConfiguredDefaultsPerType() {
        ParkingSpot spot = TestFixtures.spot("paid", null, 0, 0);
        when(violations.findActive(anyString(), any(), any())).thenReturn(Optional.empty());

        service.confirm(spot, ViolationType.WRONG_PERMIT, PLATE, "cam-1");
        service.confirm(spot, ViolationType.BOUNDARY_EXCEEDED, PLATE, "cam-1");
        service.confirm(spot, ViolationType.NO_PARKING, PLATE, "cam-1");

        ArgumentCaptor<Violation> saved = ArgumentCaptor.forClass(Violation.class);
        verify(violations, org.mockito.Mockito.times(3)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues()).extracting(v -> Duration.between(NOW, v.getGraceUntil()).toMinutes())
                .containsExactly(30L, 10L, 10L);
    }

    @Test
    void anExistingActiveViolationIsOnlyTouchedWhenTheLastWriteIsOldEnough() {
        ParkingSpot spot = TestFixtures.spot("permit", "resident", 0, 0);
        Violation fresh = TestFixtures.violation(ViolationType.WRONG_PERMIT, NOW.minusSeconds(5), NOW.plusSeconds(600));
        when(violations.findActive(PLATE, spot.id(), ViolationType.WRONG_PERMIT)).thenReturn(Optional.of(fresh));

        service.confirm(spot, ViolationType.WRONG_PERMIT, PLATE, "cam-1");

        verify(violations, never()).touch(any(), any());
        verify(violations, never()).saveAndFlush(any());

        Violation old = TestFixtures.violation(ViolationType.WRONG_PERMIT, NOW.minusSeconds(120), NOW.plusSeconds(600));
        when(violations.findActive(PLATE, spot.id(), ViolationType.WRONG_PERMIT)).thenReturn(Optional.of(old));

        service.confirm(spot, ViolationType.WRONG_PERMIT, PLATE, "cam-1");

        verify(violations).touch(old.getId(), NOW);
        verify(violations, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    @Test
    void aVehicleThatWasAlreadyFinedAndIsStillThereDoesNotGetASecondViolation() {
        ParkingSpot spot = TestFixtures.spot("permit", "resident", 0, 0);
        UUID finedId = UUID.randomUUID();
        when(violations.findActive(PLATE, spot.id(), ViolationType.WRONG_PERMIT)).thenReturn(Optional.empty());
        when(violations.findRecentlyFinedIds(eq(PLATE), eq(spot.id()), eq(ViolationType.WRONG_PERMIT),
                eq(NOW.minus(Duration.ofMinutes(15))), any(Pageable.class))).thenReturn(List.of(finedId));

        service.confirm(spot, ViolationType.WRONG_PERMIT, PLATE, "cam-1");

        verify(violations).touch(finedId, NOW);
        verify(violations, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    @Test
    void aLostInsertRaceIsTreatedAsAlreadyExisting() {
        ParkingSpot spot = TestFixtures.spot("permit", "resident", 0, 0);
        Violation winner = TestFixtures.violation(ViolationType.WRONG_PERMIT, NOW, NOW.plusSeconds(600));
        when(violations.findActive(PLATE, spot.id(), ViolationType.WRONG_PERMIT))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(violations.saveAndFlush(any(Violation.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));

        service.confirm(spot, ViolationType.WRONG_PERMIT, PLATE, "cam-1");

        verifyNoInteractions(events);
    }

    @Test
    void anIntegrityErrorThatIsNotADuplicateIsNotSwallowed() {
        ParkingSpot spot = TestFixtures.spot("permit", "resident", 0, 0);
        when(violations.findActive(PLATE, spot.id(), ViolationType.WRONG_PERMIT)).thenReturn(Optional.empty());
        when(violations.saveAndFlush(any(Violation.class))).thenThrow(new DataIntegrityViolationException("fk violation"));

        assertThatThrownBy(() -> service.confirm(spot, ViolationType.WRONG_PERMIT, PLATE, "cam-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void resolveOthersResolvesEverythingExceptTheKeptViolation() {
        UUID keepSpot = UUID.randomUUID();
        Violation keep = violationAt(keepSpot, ViolationType.WRONG_PERMIT);
        Violation otherType = violationAt(keepSpot, ViolationType.BOUNDARY_EXCEEDED);
        Violation otherSpot = violationAt(UUID.randomUUID(), ViolationType.WRONG_PERMIT);
        when(violations.findActiveByPlateAndZone(PLATE, ZONE)).thenReturn(List.of(keep, otherType, otherSpot));
        when(violations.resolveIfActive(any(), any(), any())).thenReturn(1);

        service.resolveOthers(PLATE, ZONE, keepSpot, ViolationType.WRONG_PERMIT, ResolutionReason.CORRECTED);

        verify(violations, never()).resolveIfActive(eq(keep.getId()), any(), any());
        verify(violations).resolveIfActive(otherType.getId(), ResolutionReason.CORRECTED, NOW);
        verify(violations).resolveIfActive(otherSpot.getId(), ResolutionReason.CORRECTED, NOW);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events, org.mockito.Mockito.times(2)).publish(eq(RabbitConfig.VIOLATION_EXCHANGE), eq("violation.resolved"), payload.capture());
        ViolationResolvedEvent event = (ViolationResolvedEvent) payload.getAllValues().get(0);
        assertThat(event.reason()).isEqualTo(ResolutionReason.CORRECTED);
        assertThat(event.plate()).isEqualTo(PLATE);
        assertThat(event.occurredAt()).isEqualTo(NOW);
    }

    @Test
    void resolveOthersWithoutKeepResolvesAll() {
        Violation a = violationAt(UUID.randomUUID(), ViolationType.WRONG_PERMIT);
        when(violations.findActiveByPlateAndZone(PLATE, ZONE)).thenReturn(List.of(a));
        when(violations.resolveIfActive(any(), any(), any())).thenReturn(1);

        service.resolveOthers(PLATE, ZONE, null, null, ResolutionReason.LEFT);

        verify(violations).resolveIfActive(a.getId(), ResolutionReason.LEFT, NOW);
    }

    @Test
    void noEventWhenSomeoneElseResolvedItFirst() {
        Violation a = violationAt(UUID.randomUUID(), ViolationType.WRONG_PERMIT);
        when(violations.findActiveByPlateAndZone(PLATE, ZONE)).thenReturn(List.of(a));
        when(violations.resolveIfActive(any(), any(), any())).thenReturn(0);

        service.resolveOthers(PLATE, ZONE, null, null, ResolutionReason.LEFT);

        verifyNoInteractions(events);
    }

    @Test
    void staleViolationsAreResolvedAsLeft() {
        Violation stale = violationAt(UUID.randomUUID(), ViolationType.BOUNDARY_EXCEEDED);
        when(violations.findStale(eq(NOW.minus(Duration.ofMinutes(15))), any(Pageable.class))).thenReturn(List.of(stale));
        when(violations.resolveIfActive(any(), any(), any())).thenReturn(1);

        assertThat(service.resolveStale()).isEqualTo(1);

        verify(violations).resolveIfActive(stale.getId(), ResolutionReason.LEFT, NOW);
    }

    @Test
    void manualResolveClosesAnOpenViolationAndPublishes() {
        Violation open = violationAt(UUID.randomUUID(), ViolationType.WRONG_PERMIT);
        when(violations.findById(open.getId())).thenReturn(Optional.of(open));
        when(violations.resolveIfActive(open.getId(), ResolutionReason.MANUAL, NOW)).thenReturn(1);

        service.resolveManually(open.getId());

        verify(events).publish(eq(RabbitConfig.VIOLATION_EXCHANGE), eq("violation.resolved"), any(ViolationResolvedEvent.class));
    }

    @Test
    void manualResolveOfAnAlreadyResolvedViolationIsANoOp() {
        Violation resolved = violationAt(UUID.randomUUID(), ViolationType.WRONG_PERMIT);
        ReflectionTestUtils.setField(resolved, "resolvedAt", NOW.minusSeconds(60));
        when(violations.findById(resolved.getId())).thenReturn(Optional.of(resolved));
        when(violations.resolveIfActive(any(), any(), any())).thenReturn(0);

        service.resolveManually(resolved.getId());

        verifyNoInteractions(events);
    }

    @Test
    void manualResolveOfAFinedViolationIsAConflict() {
        Violation fined = violationAt(UUID.randomUUID(), ViolationType.WRONG_PERMIT);
        fined.markFined(UUID.randomUUID(), NOW.minusSeconds(60));
        when(violations.findById(fined.getId())).thenReturn(Optional.of(fined));

        assertThatThrownBy(() -> service.resolveManually(fined.getId())).isInstanceOf(ConflictException.class);
    }

    @Test
    void manualResolveOfAnUnknownViolationIsNotFound() {
        UUID id = UUID.randomUUID();
        when(violations.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveManually(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    private static Violation violationAt(UUID spotId, ViolationType type) {
        Violation v = TestFixtures.violation(type, NOW.minusSeconds(300), NOW.plusSeconds(300));
        ReflectionTestUtils.setField(v, "spotId", spotId);
        return v;
    }
}
