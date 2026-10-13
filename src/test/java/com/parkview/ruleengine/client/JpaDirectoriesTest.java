package com.parkview.ruleengine.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkview.ruleengine.domain.ParkingSpot;
import com.parkview.ruleengine.geometry.PolygonGeometry;
import com.parkview.ruleengine.readmodel.ParkingSpotViewRepository;
import com.parkview.ruleengine.readmodel.PlateOwnerViewRepository;
import com.parkview.ruleengine.readmodel.ZoneViewRepository;
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

/** The tables read here belong to other services; test-schema.sql creates them with the columns the read models do not map too. */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaDirectoriesTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withInitScript("test-schema.sql");

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ParkingSpotViewRepository spotViews;
    @Autowired
    PlateOwnerViewRepository plateViews;
    @Autowired
    ZoneViewRepository zoneViews;

    private JpaSpotDirectory spots;
    private JpaPlateOwnerLookup owners;
    private JpaZoneDirectory zones;

    @BeforeEach
    void adapters() {
        jdbc.update("delete from parking_spots");
        jdbc.update("delete from user_plates");
        jdbc.update("delete from zones");
        spots = new JpaSpotDirectory(spotViews, new ObjectMapper());
        owners = new JpaPlateOwnerLookup(plateViews);
        zones = new JpaZoneDirectory(zoneViews);
    }

    private void insertSpot(String zone, String shape, String type, String permit, String coordinates, Integer boundary, Integer permitGrace) {
        jdbc.update("""
                insert into parking_spots (zone_id, type, shape, coordinates, spot_number, permit_type, boundary_grace_min, permit_grace_min)
                values (?, ?, ?, ?::jsonb, 'A1', ?, ?, ?)""", zone, type, shape, coordinates, permit, boundary, permitGrace);
    }

    @Test
    void polygonSpotsAreParsedFromJsonbInEitherWinding() {
        insertSpot("z1", "polygon", "permit", "resident", "[[0, 0], [0, 10], [10, 10], [10, 0]]", 10, 30);
        insertSpot("z1", "polygon", "paid", null, "[[0, 0], [10, 0], [10, 10], [0, 10]]", null, null);

        List<ParkingSpot> found = spots.polygonSpotsOf("z1");

        assertThat(found).hasSize(2);
        assertThat(found).allSatisfy(s -> assertThat(PolygonGeometry.area(s.polygon())).isEqualTo(100.0));
        ParkingSpot permit = found.stream().filter(s -> "permit".equals(s.type())).findFirst().orElseThrow();
        assertThat(permit.permitType()).isEqualTo("resident");
        assertThat(permit.boundaryGraceMin()).isEqualTo(10);
        assertThat(permit.permitGraceMin()).isEqualTo(30);
        assertThat(permit.spotNumber()).isEqualTo("A1");
        assertThat(PolygonGeometry.signedArea(permit.polygon())).isNegative();
        ParkingSpot paid = found.stream().filter(s -> "paid".equals(s.type())).findFirst().orElseThrow();
        assertThat(paid.boundaryGraceMin()).isZero();
        assertThat(paid.permitGraceMin()).isZero();
    }

    @Test
    void pointSpotsOtherZonesAndBrokenPolygonsAreIgnored() {
        insertSpot("z1", "point", "paid", null, "[59.3, 18.0]", null, null);
        insertSpot("z2", "polygon", "paid", null, "[[0, 0], [0, 10], [10, 10]]", null, null);
        insertSpot("z1", "polygon", "paid", null, "[[0, 0], [1, 1]]", null, null);
        insertSpot("z1", "polygon", "paid", null, "[[0, 0], [0, 10], [10, 10]]", null, null);

        assertThat(spots.polygonSpotsOf("z1")).hasSize(1);
        assertThat(spots.polygonSpotsOf("unknown")).isEmpty();
    }

    @Test
    void coordinatesOfTheWrongJsonShapeSkipOnlyThatSpot() {
        insertSpot("z1", "polygon", "paid", null, "\"not-a-polygon\"", null, null);
        insertSpot("z1", "polygon", "paid", null, "{\"x\": 1}", null, null);
        insertSpot("z1", "polygon", "paid", null, "[[0, 0], [0, \"a\"], [10, 10]]", null, null);
        insertSpot("z1", "polygon", "paid", null, "[[0, 0], [0, 10], [10, 10]]", null, null);

        assertThat(spots.polygonSpotsOf("z1")).hasSize(1);
    }

    @Test
    void plateOwnerAndZoneAddressLookups() {
        UUID user = UUID.randomUUID();
        jdbc.update("insert into user_plates (user_id, plate) values (?, 'ABC123')", user);
        jdbc.update("insert into zones (id, address) values ('z1', 'Storgatan 1'), ('z2', null), ('z3', '  ')");

        assertThat(owners.ownerOf("ABC123")).contains(user);
        assertThat(owners.ownerOf("NOPE11")).isEmpty();
        assertThat(zones.addressOf("z1")).contains("Storgatan 1");
        assertThat(zones.addressOf("z2")).isEmpty();
        assertThat(zones.addressOf("z3")).isEmpty();
        assertThat(zones.addressOf("z9")).isEmpty();
    }
}
