package com.parkview.ruleengine.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkview.ruleengine.domain.ParkingSpot;
import com.parkview.ruleengine.geometry.PolygonGeometry;
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

/** The tables read here belong to other services; the test creates the minimal shape the queries rely on. */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JdbcDirectoriesTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    JdbcTemplate jdbc;

    private JdbcSpotDirectory spots;
    private JdbcPlateOwnerLookup owners;
    private JdbcZoneDirectory zones;

    @BeforeEach
    void schema() {
        jdbc.execute("""
                create table if not exists zones (id text primary key, address text);
                create table if not exists user_plates (id uuid primary key default gen_random_uuid(),
                    user_id uuid not null, plate text not null unique);
                create table if not exists parking_spots (id uuid primary key default gen_random_uuid(),
                    zone_id text not null, type text not null, shape text not null, coordinates jsonb not null,
                    spot_number text, permit_type text, boundary_grace_min integer, permit_grace_min integer);
                """);
        jdbc.update("delete from parking_spots");
        jdbc.update("delete from user_plates");
        jdbc.update("delete from zones");
        ObjectMapper mapper = new ObjectMapper();
        spots = new JdbcSpotDirectory(jdbc, mapper);
        owners = new JdbcPlateOwnerLookup(jdbc);
        zones = new JdbcZoneDirectory(jdbc);
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
    void plateOwnerAndZoneAddressLookups() {
        UUID user = UUID.randomUUID();
        jdbc.update("insert into user_plates (user_id, plate) values (?, 'ABC123')", user);
        jdbc.update("insert into zones (id, address) values ('z1', 'Storgatan 1'), ('z2', null)");

        assertThat(owners.ownerOf("ABC123")).contains(user);
        assertThat(owners.ownerOf("NOPE11")).isEmpty();
        assertThat(zones.addressOf("z1")).contains("Storgatan 1");
        assertThat(zones.addressOf("z2")).isEmpty();
        assertThat(zones.addressOf("z9")).isEmpty();
    }
}
