package com.parkview.ruleengine.service;

import com.parkview.ruleengine.domain.PlatePermit;
import com.parkview.ruleengine.dto.request.CreatePermitRequest;
import com.parkview.ruleengine.repository.PlatePermitRepository;
import com.parkview.ruleengine.web.BusinessRuleException;
import com.parkview.ruleengine.web.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PermitServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    private final PlatePermitRepository repository = mock(PlatePermitRepository.class);
    private PermitService service;

    @BeforeEach
    void setUp() {
        service = new PermitService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.save(any(PlatePermit.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void grantNormalisesThePlateAndDefaultsValidFromToNow() {
        PlatePermit permit = service.grant(new CreatePermitRequest("abc-123 ", " resident ", " zone-1 ", null, null), "user-1");

        assertThat(permit.getPlate()).isEqualTo("ABC123");
        assertThat(permit.getPermitType()).isEqualTo("resident");
        assertThat(permit.getZoneId()).isEqualTo("zone-1");
        assertThat(permit.getValidFrom()).isEqualTo(NOW);
        assertThat(permit.getValidTo()).isNull();
        assertThat(permit.getCreatedBy()).isEqualTo("user-1");
        assertThat(permit.isGlobal()).isFalse();
    }

    @Test
    void blankZoneMeansAGlobalPermit() {
        PlatePermit permit = service.grant(new CreatePermitRequest("ABC123", "resident", "  ", null, null), "user-1");

        assertThat(permit.getZoneId()).isNull();
        assertThat(permit.isGlobal()).isTrue();
    }

    @Test
    void validToMustBeAfterValidFrom() {
        assertThatThrownBy(() -> service.grant(
                new CreatePermitRequest("ABC123", "resident", null, NOW, NOW), "user-1"))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.grant(
                new CreatePermitRequest("ABC123", "resident", null, null, NOW.minusSeconds(1)), "user-1"))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void aPlateWithoutAlphanumericCharactersIsRejected() {
        assertThatThrownBy(() -> service.grant(new CreatePermitRequest(" - ", "resident", null, null, null), "user-1"))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void revokeDeletesAndUnknownIdIsNotFound() {
        PlatePermit permit = PlatePermit.grant("ABC123", "resident", null, NOW, null, "u");
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(permit));

        service.revoke(id);

        verify(repository).delete(permit);

        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.revoke(unknown)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void hasValidPermitDelegatesToTheRepository() {
        when(repository.existsValid("ABC123", "resident", "zone-1", NOW)).thenReturn(true);

        assertThat(service.hasValidPermit("ABC123", "zone-1", "resident", NOW)).isTrue();
        assertThat(service.hasValidPermit("ABC123", "zone-2", "resident", NOW)).isFalse();
    }
}
