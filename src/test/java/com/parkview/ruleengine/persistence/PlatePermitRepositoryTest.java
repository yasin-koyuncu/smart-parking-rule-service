package com.parkview.ruleengine.persistence;

import com.parkview.ruleengine.domain.PlatePermit;
import com.parkview.ruleengine.dto.request.CreatePermitRequest;
import com.parkview.ruleengine.repository.PlatePermitRepository;
import com.parkview.ruleengine.service.PermitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PermitService.class, PlatePermitRepositoryTest.FixedClock.class})
class PlatePermitRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    @org.springframework.boot.test.context.TestConfiguration
    static class FixedClock {
        @org.springframework.context.annotation.Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    PlatePermitRepository repository;
    @Autowired
    PermitService service;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from plate_permits");
    }

    private PlatePermit grant(String plate, String type, String zone, Instant from, Instant to) {
        return repository.saveAndFlush(PlatePermit.grant(plate, type, zone, from, to, "operator-1"));
    }

    @Test
    void zoneSpecificPermitCoversOnlyItsZone() {
        grant("ABC123", "resident", "z1", NOW.minusSeconds(60), null);

        assertThat(repository.existsValid("ABC123", "resident", "z1", NOW)).isTrue();
        assertThat(repository.existsValid("ABC123", "resident", "z2", NOW)).isFalse();
    }

    @Test
    void globalPermitCoversEveryZone() {
        grant("ABC123", "resident", null, NOW.minusSeconds(60), null);

        assertThat(repository.existsValid("ABC123", "resident", "z1", NOW)).isTrue();
        assertThat(repository.existsValid("ABC123", "resident", "anywhere", NOW)).isTrue();
    }

    @Test
    void permitTypeMatchesCaseInsensitivelyAndMustBeTheRequiredOne() {
        grant("ABC123", "Resident", "z1", NOW.minusSeconds(60), null);

        assertThat(repository.existsValid("ABC123", "RESIDENT", "z1", NOW)).isTrue();
        assertThat(repository.existsValid("ABC123", "visitor", "z1", NOW)).isFalse();
    }

    @Test
    void otherPlatesDoNotMatch() {
        grant("ABC123", "resident", "z1", NOW.minusSeconds(60), null);

        assertThat(repository.existsValid("XYZ789", "resident", "z1", NOW)).isFalse();
    }

    @Test
    void validityWindowIsRespected() {
        grant("FUT111", "resident", "z1", NOW.plusSeconds(60), null);
        grant("EXP111", "resident", "z1", NOW.minusSeconds(3600), NOW.minusSeconds(1));
        grant("END111", "resident", "z1", NOW.minusSeconds(3600), NOW);
        grant("OK1111", "resident", "z1", NOW.minusSeconds(3600), NOW.plusSeconds(1));

        assertThat(repository.existsValid("FUT111", "resident", "z1", NOW)).isFalse();
        assertThat(repository.existsValid("EXP111", "resident", "z1", NOW)).isFalse();
        assertThat(repository.existsValid("END111", "resident", "z1", NOW)).isFalse();
        assertThat(repository.existsValid("OK1111", "resident", "z1", NOW)).isTrue();
        assertThat(repository.existsValid("FUT111", "resident", "z1", NOW.plusSeconds(60))).isTrue();
    }

    @Test
    void databaseRejectsAnInvertedValidityRange() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into plate_permits (plate, permit_type, valid_from, valid_to, created_by)
                values ('ABC123', 'resident', now(), now() - interval '1 day', 'x')"""))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void serviceSearchFiltersByPlateZoneAndVisibleZones() {
        service.grant(new CreatePermitRequest("ABC 123", "resident", "z1", null, null), "op");
        service.grant(new CreatePermitRequest("ABC123", "visitor", "z2", null, null), "op");
        service.grant(new CreatePermitRequest("ABC123", "resident", null, null, null), "op");
        service.grant(new CreatePermitRequest("XYZ789", "resident", "z1", null, null), "op");
        PageRequest page = PageRequest.of(0, 50);

        assertThat(service.search(null, null, null, page).getTotalElements()).isEqualTo(4);
        assertThat(service.search("abc-123", null, null, page).getTotalElements()).isEqualTo(3);
        assertThat(service.search(null, "z1", null, page).getTotalElements()).isEqualTo(2);
        assertThat(service.search(null, null, List.of("z1"), page).getContent())
                .extracting(PlatePermit::getZoneId).containsExactlyInAnyOrder("z1", "z1", null);
        assertThat(service.search(null, null, List.of(), page).getContent())
                .extracting(PlatePermit::getZoneId).containsOnlyNulls();
    }

    @Test
    void permitServiceDecidesAtTheGivenInstant() {
        service.grant(new CreatePermitRequest("ABC123", "resident", "z1", NOW.plusSeconds(3600), null), "op");

        assertThat(service.hasValidPermit("ABC123", "z1", "resident", NOW)).isFalse();
        assertThat(service.hasValidPermit("ABC123", "z1", "resident", NOW.plusSeconds(3600))).isTrue();
    }
}
