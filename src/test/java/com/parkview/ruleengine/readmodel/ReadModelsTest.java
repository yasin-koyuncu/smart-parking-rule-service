package com.parkview.ruleengine.readmodel;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The read models against a Postgres whose Supabase-style tables have more columns than are mapped
 * (schema validation is on, see application.yml), and proof that they cannot be used to write.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ReadModelsTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withInitScript("test-schema.sql");

    private static final List<Class<?>> VIEWS = List.of(ZoneView.class, PlateOwnerView.class, ParkingSpotView.class, ZoneRuleView.class, SessionView.class);
    private static final List<Class<?>> REPOSITORIES =
            List.of(ZoneViewRepository.class, PlateOwnerViewRepository.class, ParkingSpotViewRepository.class,
                    ZoneRuleViewRepository.class, SessionViewRepository.class);

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EntityManager entityManager;
    @Autowired
    Environment environment;
    @Autowired
    ZoneViewRepository zones;
    @Autowired
    PlateOwnerViewRepository plates;
    @Autowired
    ParkingSpotViewRepository spots;
    @Autowired
    ZoneRuleViewRepository zoneRules;
    @Autowired
    SessionViewRepository sessions;

    @BeforeEach
    void clean() {
        jdbc.update("delete from sessions");
        jdbc.update("delete from zone_rules");
        jdbc.update("delete from parking_spots");
        jdbc.update("delete from user_plates");
        jdbc.update("delete from zones");
    }

    private UUID insertSpot(String zone, String shape, String coordinates) {
        return jdbc.queryForObject("""
                insert into parking_spots (zone_id, type, shape, coordinates, spot_number, permit_type, boundary_grace_min, permit_grace_min, label, price_per_hour, max_minutes)
                values (?, 'permit', ?, ?::jsonb, 'A1', 'resident', 12, 34, 'extra', 9.5, 45) returning id""",
                UUID.class, zone, shape, coordinates);
    }

    @Test
    void schemaValidationIsOnAndTheTablesHaveMoreColumnsThanMapped() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(columnCount("zones")).isGreaterThan(2);
        assertThat(columnCount("user_plates")).isGreaterThan(3);
        assertThat(columnCount("parking_spots")).isGreaterThan(9);
        assertThat(columnCount("zone_rules")).isGreaterThan(14);
        assertThat(columnCount("sessions")).isGreaterThan(6);
    }

    private int columnCount(String table) {
        return jdbc.queryForObject("select count(*) from information_schema.columns where table_name = ?", Integer.class, table);
    }

    @Test
    void zoneAndPlateViewsLoadDespiteExtraColumns() {
        UUID user = UUID.randomUUID();
        jdbc.update("insert into zones (id, address, name, price_per_hour) values ('z1', 'Storgatan 1', 'Central', 20)");
        jdbc.update("insert into user_plates (user_id, plate, label) values (?, 'ABC123', 'My car')", user);

        assertThat(zones.findById("z1")).get().extracting(ZoneView::getAddress).isEqualTo("Storgatan 1");
        assertThat(zones.findById("missing")).isEmpty();
        assertThat(plates.findFirstByPlate("ABC123")).get().extracting(PlateOwnerView::getUserId).isEqualTo(user);
        assertThat(plates.findFirstByPlate("NOPE11")).isEmpty();
    }

    @Test
    void zoneViewCarriesStayLimitCountryAndTimezone() {
        jdbc.update("insert into zones (id, address, max_minutes, country, timezone) values ('z1', 'Storgatan 1', 120, 'NO', 'Europe/Oslo')");
        jdbc.update("insert into zones (id) values ('z2')");

        ZoneView oslo = zones.findById("z1").orElseThrow();
        assertThat(oslo.getMaxMinutes()).isEqualTo(120);
        assertThat(oslo.getCountry()).isEqualTo("NO");
        assertThat(oslo.getTimezone()).isEqualTo("Europe/Oslo");
        ZoneView defaults = zones.findById("z2").orElseThrow();
        assertThat(defaults.getMaxMinutes()).isZero();
        assertThat(defaults.getCountry()).isEqualTo("SE");
        assertThat(defaults.getTimezone()).isEqualTo("Europe/Stockholm");
    }

    @Test
    void zoneRuleViewLoadsTimeColumnsAndArraysOfEnabledRulesOnly() {
        jdbc.update("""
                insert into zone_rules (zone_id, label, priority, enabled, vehicle_types, permit_required, weekdays,
                                        start_time, end_time, applies_holidays, max_minutes, price_per_hour, no_parking, note)
                values ('z1', 'Night', 5, true, '{truck,bus}', 'resident', '{1,2,3,4,5}', '22:00', '06:30:15', false, 90, 20, true, 'extra')""");
        jdbc.update("insert into zone_rules (zone_id, label, enabled) values ('z1', 'Disabled', false)");
        jdbc.update("insert into zone_rules (zone_id, label) values ('z2', 'Other zone')");
        jdbc.update("insert into zone_rules (zone_id, label) values ('z1', 'Defaults')");

        List<ZoneRuleView> found = zoneRules.findByZoneIdAndEnabledTrue("z1");

        assertThat(found).extracting(ZoneRuleView::getLabel).containsExactlyInAnyOrder("Night", "Defaults");
        ZoneRuleView night = found.stream().filter(r -> r.getLabel().equals("Night")).findFirst().orElseThrow();
        assertThat(night.getZoneId()).isEqualTo("z1");
        assertThat(night.getPriority()).isEqualTo(5);
        assertThat(night.isEnabled()).isTrue();
        assertThat(night.getVehicleTypes()).containsExactly("truck", "bus");
        assertThat(night.getPermitRequired()).isEqualTo("resident");
        assertThat(night.getWeekdays()).containsExactly(1, 2, 3, 4, 5);
        assertThat(night.getStartTime()).isEqualTo("22:00:00");
        assertThat(night.getEndTime()).isEqualTo("06:30:15");
        assertThat(night.isAppliesHolidays()).isFalse();
        assertThat(night.getMaxMinutes()).isEqualTo(90);
        assertThat(night.isNoParking()).isTrue();

        ZoneRuleView defaults = found.stream().filter(r -> r.getLabel().equals("Defaults")).findFirst().orElseThrow();
        assertThat(defaults.getPriority()).isEqualTo(100);
        assertThat(defaults.getVehicleTypes()).isEmpty();
        assertThat(defaults.getPermitRequired()).isNull();
        assertThat(defaults.getWeekdays()).containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(defaults.getStartTime()).isEqualTo("00:00:00");
        assertThat(defaults.getEndTime()).isEqualTo("23:59:00");
        assertThat(defaults.isAppliesHolidays()).isTrue();
        assertThat(defaults.getMaxMinutes()).isNull();
        assertThat(defaults.isNoParking()).isFalse();
        assertThat(zoneRules.findByZoneIdAndEnabledTrue("unknown")).isEmpty();
    }

    @Test
    void sessionViewFindsTheNewestActiveSessionOfAPlateInAZone() {
        jdbc.update("insert into sessions (plate, zone_id, started_at, ended_at) values ('ABC123', 'z1', '2026-10-12T06:00:00Z', '2026-10-12T07:00:00Z')");
        jdbc.update("insert into sessions (plate, zone_id, started_at) values ('ABC123', 'z1', '2026-10-12T08:00:00Z')");
        jdbc.update("insert into sessions (plate, zone_id, started_at) values ('ABC123', 'z1', '2026-10-12T09:00:00Z')");
        jdbc.update("insert into sessions (plate, zone_id, started_at) values ('ABC123', 'z2', '2026-10-12T09:30:00Z')");
        jdbc.update("insert into sessions (plate, zone_id, started_at) values ('XYZ789', 'z1', '2026-10-12T09:45:00Z')");

        SessionView active = sessions.findFirstByPlateAndSiteIdAndZoneIdAndEndedAtIsNullOrderByStartedAtDesc("ABC123", "z1", "z1").orElseThrow();

        assertThat(active.getPlate()).isEqualTo("ABC123");
        assertThat(active.getZoneId()).isEqualTo("z1");
        assertThat(active.getStartedAt()).isEqualTo(Instant.parse("2026-10-12T09:00:00Z"));
        assertThat(active.getEndedAt()).isNull();
        assertThat(sessions.findFirstByPlateAndSiteIdAndZoneIdAndEndedAtIsNullOrderByStartedAtDesc("ABC123", "z3", "z3")).isEmpty();
    }

    @Test
    void parkingSpotViewLoadsJsonbCoordinatesAndOnlyPolygonSpots() {
        UUID polygon = insertSpot("z1", "polygon", "[[0, 0], [0, 10], [10, 10], [10, 0]]");
        insertSpot("z1", "point", "[59.3, 18.0]");
        insertSpot("z2", "polygon", "[[1, 1], [1, 2], [2, 2]]");

        List<ParkingSpotView> found = spots.findPolygonSpotsBySiteId("z1");

        assertThat(found).hasSize(1);
        ParkingSpotView view = found.get(0);
        assertThat(view.getId()).isEqualTo(polygon);
        assertThat(view.getZoneId()).isEqualTo("z1");
        assertThat(view.getShape()).isEqualTo("polygon");
        assertThat(view.getType()).isEqualTo("permit");
        assertThat(view.getPermitType()).isEqualTo("resident");
        assertThat(view.getSpotNumber()).isEqualTo("A1");
        assertThat(view.getBoundaryGraceMin()).isEqualTo(12);
        assertThat(view.getPermitGraceMin()).isEqualTo(34);
        assertThat(view.getMaxMinutes()).isEqualTo(45);
        assertThat(view.getCoordinates()).isEqualTo("[[0, 0], [0, 10], [10, 10], [10, 0]]");
    }

    private UUID insertViewSpot(String zone, UUID viewId, String imagePolygon) {
        return jdbc.queryForObject("""
                insert into parking_spots (zone_id, type, shape, coordinates, spot_number, view_id, image_polygon, boundary_grace_min)
                values (?, 'paid', 'polygon', null, 'V1', ?, ?::jsonb, 7) returning id""",
                UUID.class, zone, viewId, imagePolygon);
    }

    @Test
    void spotsOnAViewLoadWithTheirImagePolygonAndNullCoordinates() {
        UUID view = UUID.randomUUID();
        UUID onView = insertViewSpot("z1", view, "[[10, 10], [10, 90], [90, 90]]");
        insertViewSpot("z1", UUID.randomUUID(), "[[1, 1], [1, 2], [2, 2]]");
        insertViewSpot("z2", view, "[[1, 1], [1, 2], [2, 2]]");
        jdbc.update("insert into parking_spots (zone_id, type, shape, coordinates, view_id) values ('z1', 'paid', 'polygon', '[[0, 0], [0, 1], [1, 1]]'::jsonb, ?)", view);
        entityManager.clear();

        List<PlacedSpot> found = spots.findSpotsOnView(view, "z1");

        assertThat(found).hasSize(2);
        PlacedSpot placed = found.stream().filter(p -> p.spot().getId().equals(onView)).findFirst().orElseThrow();
        assertThat(placed.imagePolygon()).isEqualTo("[[10, 10], [10, 90], [90, 90]]");
        assertThat(placed.spot().getCoordinates()).isNull();
        assertThat(placed.spot().getBoundaryGraceMin()).isEqualTo(7);
        assertThat(spots.findSpotsOnView(UUID.randomUUID(), "z1")).isEmpty();
    }

    @Test
    void polygonSpotsOutsideViewsAreTheLegacyOnes() {
        UUID legacy = insertSpot("z1", "polygon", "[[0, 0], [0, 10], [10, 10]]");
        insertSpot("z1", "point", "[59.3, 18.0]");
        insertViewSpot("z1", UUID.randomUUID(), "[[1, 1], [1, 2], [2, 2]]");

        List<ParkingSpotView> found = spots.findPolygonSpotsOutsideViews("z1");

        assertThat(found).extracting(ParkingSpotView::getId).containsExactly(legacy);
        assertThat(spots.findPolygonSpotsBySiteId("z1")).hasSize(2);
    }

    @Test
    void aSpotIsFoundByIdWhetherOrNotItIsOnAView() {
        UUID legacy = insertSpot("z1", "polygon", "[[0, 0], [0, 10], [10, 10]]");
        UUID onView = insertViewSpot("z1", UUID.randomUUID(), "[[1, 1], [1, 2], [2, 2]]");

        assertThat(spots.findById(legacy)).get().extracting(ParkingSpotView::getSpotNumber).isEqualTo("A1");
        assertThat(spots.findById(onView)).get().extracting(ParkingSpotView::getSpotNumber).isEqualTo("V1");
        assertThat(spots.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void thePlacementViewIsAnImmutableSubselectSoSchemaValidationDoesNotNeedItsColumns() {
        assertThat(SpotPlacementView.class.isAnnotationPresent(Immutable.class)).isTrue();
        assertThat(SpotPlacementView.class.isAnnotationPresent(org.hibernate.annotations.Subselect.class)).isTrue();
        assertThat(SpotPlacementView.class.isAnnotationPresent(Table.class)).isFalse();
        assertThat(Arrays.stream(SpotPlacementView.class.getMethods()).map(Method::getName)).noneMatch(name -> name.startsWith("set"));
        assertThat(Modifier.isProtected(SpotPlacementView.class.getDeclaredConstructors()[0].getModifiers())).isTrue();
    }

    @Test
    void repositoriesExposeNoWriteApi() {
        for (Class<?> repository : REPOSITORIES) {
            assertThat(repository).isAssignableTo(Repository.class);
            assertThat(CrudRepository.class.isAssignableFrom(repository)).isFalse();
            assertThat(Arrays.stream(repository.getMethods()).map(Method::getName))
                    .noneMatch(name -> name.startsWith("save") || name.startsWith("delete")
                            || name.startsWith("remove") || name.startsWith("persist") || name.startsWith("flush"));
        }
    }

    @Test
    void viewsAreImmutableGetterOnlyEntities() throws NoSuchMethodException {
        for (Class<?> view : VIEWS) {
            assertThat(view.isAnnotationPresent(Immutable.class)).as(view.getSimpleName() + " @Immutable").isTrue();
            assertThat(view.isAnnotationPresent(Table.class)).isTrue();
            assertThat(Arrays.stream(view.getMethods()).map(Method::getName)).noneMatch(name -> name.startsWith("set"));
            assertThat(Modifier.isProtected(view.getDeclaredConstructor().getModifiers())).isTrue();
        }
    }

    @Test
    void modifyingAManagedViewAndFlushingWritesNothing() {
        UUID id = insertSpot("z1", "polygon", "[[0, 0], [0, 10], [10, 10]]");
        jdbc.update("insert into zones (id, address) values ('z1', 'Storgatan 1')");
        entityManager.clear();

        ParkingSpotView spot = spots.findPolygonSpotsBySiteId("z1").get(0);
        ReflectionTestUtils.setField(spot, "type", "no_parking");
        ReflectionTestUtils.setField(spot, "boundaryGraceMin", 99);
        ZoneView zone = zones.findById("z1").orElseThrow();
        ReflectionTestUtils.setField(zone, "address", "Changed 9");
        entityManager.flush();

        assertThat(jdbc.queryForMap("select type, boundary_grace_min from parking_spots where id = ?", id))
                .containsEntry("type", "permit").containsEntry("boundary_grace_min", 12);
        assertThat(jdbc.queryForObject("select address from zones where id = 'z1'", String.class)).isEqualTo("Storgatan 1");
    }

    @Test
    void mergingAModifiedDetachedViewWritesNothing() {
        UUID id = insertSpot("z1", "polygon", "[[0, 0], [0, 10], [10, 10]]");
        entityManager.clear();
        ParkingSpotView spot = spots.findPolygonSpotsBySiteId("z1").get(0);
        entityManager.detach(spot);

        ReflectionTestUtils.setField(spot, "permitType", "visitor");
        entityManager.merge(spot);
        entityManager.flush();
        entityManager.clear();

        assertThat(jdbc.queryForObject("select permit_type from parking_spots where id = ?", String.class, id)).isEqualTo("resident");
    }
}
