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

    private static final List<Class<?>> VIEWS = List.of(ZoneView.class, PlateOwnerView.class, ParkingSpotView.class);
    private static final List<Class<?>> REPOSITORIES =
            List.of(ZoneViewRepository.class, PlateOwnerViewRepository.class, ParkingSpotViewRepository.class);

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

    @BeforeEach
    void clean() {
        jdbc.update("delete from parking_spots");
        jdbc.update("delete from user_plates");
        jdbc.update("delete from zones");
    }

    private UUID insertSpot(String zone, String shape, String coordinates) {
        return jdbc.queryForObject("""
                insert into parking_spots (zone_id, type, shape, coordinates, spot_number, permit_type, boundary_grace_min, permit_grace_min, label, price_per_hour)
                values (?, 'permit', ?, ?::jsonb, 'A1', 'resident', 12, 34, 'extra', 9.5) returning id""",
                UUID.class, zone, shape, coordinates);
    }

    @Test
    void schemaValidationIsOnAndTheTablesHaveMoreColumnsThanMapped() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(columnCount("zones")).isGreaterThan(2);
        assertThat(columnCount("user_plates")).isGreaterThan(3);
        assertThat(columnCount("parking_spots")).isGreaterThan(9);
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
    void parkingSpotViewLoadsJsonbCoordinatesAndOnlyPolygonSpots() {
        UUID polygon = insertSpot("z1", "polygon", "[[0, 0], [0, 10], [10, 10], [10, 0]]");
        insertSpot("z1", "point", "[59.3, 18.0]");
        insertSpot("z2", "polygon", "[[1, 1], [1, 2], [2, 2]]");

        List<ParkingSpotView> found = spots.findPolygonSpotsByZoneId("z1");

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
        assertThat(view.getCoordinates()).isEqualTo("[[0, 0], [0, 10], [10, 10], [10, 0]]");
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

        ParkingSpotView spot = spots.findPolygonSpotsByZoneId("z1").get(0);
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
        ParkingSpotView spot = spots.findPolygonSpotsByZoneId("z1").get(0);
        entityManager.detach(spot);

        ReflectionTestUtils.setField(spot, "permitType", "visitor");
        entityManager.merge(spot);
        entityManager.flush();
        entityManager.clear();

        assertThat(jdbc.queryForObject("select permit_type from parking_spots where id = ?", String.class, id)).isEqualTo("resident");
    }
}
