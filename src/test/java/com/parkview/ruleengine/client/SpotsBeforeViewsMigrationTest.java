package com.parkview.ruleengine.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkview.ruleengine.domain.ParkingSpot;
import com.parkview.ruleengine.readmodel.ParkingSpotViewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A database that has not got parking-mapping-service's camera-view migration yet ({@code parking_spots} without
 * {@code view_id} / {@code image_polygon}, no {@code camera_views}). The application context starting at all proves
 * that schema validation does not need the new columns; the rest proves spots are still read as legacy spots.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SpotsBeforeViewsMigrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withInitScripts("test-schema.sql", "test-schema-before-views.sql");

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ParkingSpotViewRepository spotViews;

    private JpaSpotDirectory spots;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from parking_spots");
        spots = new JpaSpotDirectory(spotViews, new ObjectMapper(), new SpotViewSchema(jdbc));
    }

    @Test
    void theViewColumnsAreNotThere() {
        assertThat(new SpotViewSchema(jdbc).present()).isFalse();
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_name = 'parking_spots' and column_name in ('view_id', 'image_polygon')""", Integer.class)).isZero();
    }

    @Test
    void everyPolygonSpotIsALegacySpot() {
        jdbc.update("insert into parking_spots (zone_id, type, shape, coordinates, spot_number) values ('z1', 'paid', 'polygon', '[[0, 0], [0, 10], [10, 10]]'::jsonb, 'A1')");
        jdbc.update("insert into parking_spots (zone_id, type, shape, coordinates) values ('z1', 'paid', 'point', '[59.3, 18.0]'::jsonb)");
        jdbc.update("insert into parking_spots (zone_id, type, shape, coordinates) values ('z2', 'paid', 'polygon', '[[0, 0], [0, 10], [10, 10]]'::jsonb)");

        List<ParkingSpot> found = spots.polygonSpotsOf("z1");

        assertThat(found).hasSize(1);
        assertThat(found.get(0).spotNumber()).isEqualTo("A1");
    }

    @Test
    void thereAreNoViewSpotsAndAskingForOneDoesNotFail() {
        jdbc.update("insert into parking_spots (zone_id, type, shape, coordinates) values ('z1', 'paid', 'polygon', '[[0, 0], [0, 10], [10, 10]]'::jsonb)");

        assertThat(spots.polygonSpotsOfView("z1", UUID.randomUUID())).isEmpty();
    }

    @Test
    void theSpotNumberIsStillRead() {
        UUID id = jdbc.queryForObject("insert into parking_spots (zone_id, type, shape, coordinates, spot_number) values ('z1', 'paid', 'polygon', '[[0, 0], [0, 10], [10, 10]]'::jsonb, 'A9') returning id", UUID.class);

        assertThat(spots.spotNumberOf(id)).contains("A9");
    }
}
