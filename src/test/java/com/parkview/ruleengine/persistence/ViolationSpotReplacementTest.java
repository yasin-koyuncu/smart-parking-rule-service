package com.parkview.ruleengine.persistence;

import com.parkview.ruleengine.client.PlateOwnerDirectory;
import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.type.ViolationType;
import com.parkview.ruleengine.domain.value.ParkingSpot;
import com.parkview.ruleengine.engine.EngineMetrics;
import com.parkview.ruleengine.repository.FineRepository;
import com.parkview.ruleengine.repository.ViolationRepository;
import com.parkview.ruleengine.service.TestFixtures;
import com.parkview.ruleengine.service.cache.SpotCacheService;
import com.parkview.ruleengine.service.outbox.OutboxService;
import com.parkview.ruleengine.service.violation.ViolationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The real {@link ViolationService} against Postgres with the foreign key the shared Supabase schema has on
 * {@code spot_violations.spot_id} (rule-service's own changelog does not create it): a spot that is deleted while observations
 * for it are in flight, and a spot that is replaced (re-scan, "empty the area") after the vehicle was already fined.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ViolationSpotReplacementTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withInitScript("test-schema.sql");

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");
    private static final String ZONE = TestFixtures.ZONE;
    private static final String PLATE = TestFixtures.PLATE;

    @Autowired
    ViolationRepository violations;
    @Autowired
    FineRepository fines;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager transactionManager;

    final SpotCacheService spotCache = mock(SpotCacheService.class);
    final OutboxService outbox = mock(OutboxService.class);
    ViolationService service;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from spot_violations");
        jdbc.update("delete from fines");
        jdbc.update("delete from parking_spots");
        jdbc.execute("alter table spot_violations drop constraint if exists spot_violations_spot_fk");
        jdbc.execute("alter table spot_violations add constraint spot_violations_spot_fk "
                + "foreign key (spot_id) references parking_spots (id) on delete cascade");
        PlateOwnerDirectory owners = mock(PlateOwnerDirectory.class);
        ZoneDirectory zones = mock(ZoneDirectory.class);
        when(owners.ownerOf(anyString())).thenReturn(Optional.empty());
        when(zones.addressOf(anyString())).thenReturn(Optional.empty());
        service = new ViolationService(violations, fines, owners, zones, outbox, TestFixtures.properties(),
                new EngineMetrics(new SimpleMeterRegistry()), spotCache, Clock.fixed(NOW, ZoneOffset.UTC),
                new TransactionTemplate(transactionManager));
    }

    @AfterEach
    void dropForeignKey() {
        jdbc.execute("alter table spot_violations drop constraint if exists spot_violations_spot_fk");
    }

    private UUID insertSpot() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into parking_spots (id, zone_id, type, shape, coordinates) values (?, ?, 'permit', 'polygon', '[[0,0],[0,1],[1,1]]'::jsonb)",
                id, ZONE);
        return id;
    }

    private static ParkingSpot spotWithId(UUID id) {
        return new ParkingSpot(id, ZONE, "A1", "permit", "resident", TestFixtures.rect(0, 0, 10, 10), null, null, 0);
    }

    private int violationCount() {
        return jdbc.queryForObject("select count(*) from spot_violations", Integer.class);
    }

    @Test
    void anObservationForADeletedSpotIsDroppedTheZoneCacheEvictedAndNothingIsThrown() {
        UUID deleted = UUID.randomUUID();   // not in parking_spots: the spot was deleted after the cache loaded it

        service.confirm(spotWithId(deleted), ViolationType.WRONG_PERMIT, PLATE, "cam-1", null, NOW);

        assertThat(violationCount()).isZero();
        verify(spotCache).evict(ZONE);
        org.mockito.Mockito.verifyNoInteractions(outbox);
    }

    @Test
    void anObservationForAnExistingSpotStillOpensTheViolation() {
        UUID spot = insertSpot();

        service.confirm(spotWithId(spot), ViolationType.WRONG_PERMIT, PLATE, "cam-1", null, NOW);

        assertThat(violationCount()).isEqualTo(1);
        org.mockito.Mockito.verifyNoInteractions(spotCache);
    }

    @Test
    void aFineThatSurvivedTheReplacementOfItsSpotBlocksASecondViolationForTheSameStay() {
        UUID oldSpot = insertSpot();
        service.confirm(spotWithId(oldSpot), ViolationType.WRONG_PERMIT, PLATE, "cam-1", null, NOW.minusSeconds(3600));
        Violation violation = violations.findActive(PLATE, oldSpot, ViolationType.WRONG_PERMIT).orElseThrow();
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            Violation claimed = violations.findById(violation.getId()).orElseThrow();
            Fine fine = fines.save(Fine.issue(claimed, new BigDecimal("700"), null, NOW.minusSeconds(1800)));
            claimed.markFined(fine.getId(), NOW.minusSeconds(1800));
        });

        // re-scan: the spot is deleted (the violation cascades away) and drawn again with a new id
        jdbc.update("delete from parking_spots where id = ?", oldSpot);
        assertThat(violationCount()).as("the cascade took the violation").isZero();
        UUID newSpot = insertSpot();

        service.confirm(spotWithId(newSpot), ViolationType.WRONG_PERMIT, PLATE, "cam-1", null, NOW);

        assertThat(violationCount()).as("no second violation, so no second fine").isZero();
        assertThat(jdbc.queryForObject("select count(*) from fines", Integer.class)).isEqualTo(1);
    }

    @Test
    void aFineOlderThanTheBlockWindowOrForAnotherReasonDoesNotBlockANewViolation() {
        UUID spot = insertSpot();
        Violation old = violations.saveAndFlush(Violation.open(spot, ZONE, PLATE, null, ViolationType.WRONG_PERMIT,
                NOW.minus(Duration.ofHours(8)), NOW.minus(Duration.ofHours(8))));
        fines.save(Fine.issue(old, new BigDecimal("700"), null, NOW.minus(Duration.ofHours(7))));
        Violation other = violations.saveAndFlush(Violation.open(spot, ZONE, PLATE, null, ViolationType.OVERSTAY,
                NOW.minusSeconds(60), NOW.minusSeconds(60)));
        fines.save(Fine.issue(other, new BigDecimal("450"), null, NOW.minusSeconds(30)));
        jdbc.update("delete from spot_violations");

        service.confirm(spotWithId(spot), ViolationType.WRONG_PERMIT, PLATE, "cam-1", null, NOW);

        assertThat(violationCount()).isEqualTo(1);
    }
}
