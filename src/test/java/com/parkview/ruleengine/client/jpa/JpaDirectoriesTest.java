package com.parkview.ruleengine.client.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkview.ruleengine.domain.value.ParkingSpot;
import com.parkview.ruleengine.geometry.PolygonGeometry;
import com.parkview.ruleengine.domain.value.ZoneInfo;
import com.parkview.ruleengine.domain.value.ZoneRule;
import com.parkview.ruleengine.readmodel.ParkingSpotViewRepository;
import com.parkview.ruleengine.readmodel.PlateOwnerViewRepository;
import com.parkview.ruleengine.readmodel.SessionViewRepository;
import com.parkview.ruleengine.readmodel.ZoneRuleViewRepository;
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

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
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
    @Autowired
    ZoneRuleViewRepository ruleViews;
    @Autowired
    SessionViewRepository sessionViews;

    private JpaSpotDirectory spots;
    private JpaPlateOwnerDirectory owners;
    private JpaZoneDirectory zones;
    private JpaZoneRuleDirectory rules;
    private JpaActiveSessionDirectory sessions;

    @BeforeEach
    void adapters() {
        jdbc.update("delete from sessions");
        jdbc.update("delete from zone_rules");
        jdbc.update("delete from parking_spots");
        jdbc.update("delete from user_plates");
        jdbc.update("delete from zones");
        spots = new JpaSpotDirectory(spotViews, new ObjectMapper(), new SpotViewSchema(jdbc));
        owners = new JpaPlateOwnerDirectory(plateViews);
        zones = new JpaZoneDirectory(zoneViews);
        rules = new JpaZoneRuleDirectory(ruleViews);
        sessions = new JpaActiveSessionDirectory(sessionViews);
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
        assertThat(permit.maxMinutes()).isZero();
        assertThat(permit.spotNumber()).isEqualTo("A1");
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

    private UUID insertViewSpot(String zone, UUID viewId, String shape, String imagePolygon, String coordinates) {
        return jdbc.queryForObject("""
                insert into parking_spots (zone_id, type, shape, coordinates, spot_number, view_id, image_polygon)
                values (?, 'permit', ?, ?::jsonb, 'V1', ?, ?::jsonb) returning id""",
                UUID.class, zone, shape, coordinates, viewId, imagePolygon);
    }

    @Test
    void legacySpotsAreThoseDrawnOnNoViewAndAnUnpositionedGeoSpotIsSkipped() {
        UUID view = UUID.randomUUID();
        insertSpot("z1", "polygon", "paid", null, "[[0, 0], [0, 10], [10, 10]]", null, null);
        insertViewSpot("z1", view, "polygon", "[[5, 5], [5, 50], [50, 50]]", "[[0, 0], [0, 10], [10, 10]]");
        jdbc.update("insert into parking_spots (zone_id, type, shape, coordinates) values ('z1', 'paid', 'polygon', null)");

        List<ParkingSpot> found = spots.polygonSpotsOf("z1");

        assertThat(found).hasSize(1);
        assertThat(found.get(0).spotNumber()).isEqualTo("A1");
    }

    @Test
    void viewSpotsUseTheImagePolygonOfThatViewOnly() {
        UUID view = UUID.randomUUID();
        UUID otherView = UUID.randomUUID();
        UUID geoSpot = insertViewSpot("z1", view, "geo", "[[0, 0], [0, 20], [20, 20], [20, 0]]", null);
        insertViewSpot("z1", otherView, "polygon", "[[1, 1], [1, 9], [9, 9]]", "[[1, 1], [1, 9], [9, 9]]");
        insertViewSpot("z2", view, "polygon", "[[1, 1], [1, 9], [9, 9]]", null);
        insertViewSpot("z1", view, "polygon", null, "[[1, 1], [1, 9], [9, 9]]");
        insertViewSpot("z1", view, "polygon", "[[1, 1], [2, 2]]", null);
        insertSpot("z1", "polygon", "paid", null, "[[0, 0], [0, 10], [10, 10]]", null, null);

        List<ParkingSpot> found = spots.polygonSpotsOfView("z1", view);

        assertThat(found).hasSize(1);
        ParkingSpot spot = found.get(0);
        assertThat(spot.id()).isEqualTo(geoSpot);
        assertThat(spot.zoneId()).isEqualTo("z1");
        assertThat(spot.spotNumber()).isEqualTo("V1");
        assertThat(spot.type()).isEqualTo("permit");
        assertThat(PolygonGeometry.area(spot.polygon())).isEqualTo(400.0);
        assertThat(spots.polygonSpotsOfView("z1", UUID.randomUUID())).isEmpty();
    }

    @Test
    void theSpotNumberIsReadForLegacyAndViewSpots() {
        UUID view = UUID.randomUUID();
        insertSpot("z1", "polygon", "paid", null, "[[0, 0], [0, 10], [10, 10]]", null, null);
        UUID onView = insertViewSpot("z1", view, "polygon", "[[1, 1], [1, 9], [9, 9]]", null);
        UUID legacy = jdbc.queryForObject("select id from parking_spots where view_id is null", UUID.class);

        assertThat(spots.spotNumberOf(legacy)).contains("A1");
        assertThat(spots.spotNumberOf(onView)).contains("V1");
        assertThat(spots.spotNumberOf(UUID.randomUUID())).isEmpty();
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

    @Test
    void zoneInfoAppliesDefaultsForNullColumns() {
        jdbc.update("insert into zones (id, address, max_minutes, country, timezone) values ('z1', 'Storgatan 1', 90, 'NO', 'Europe/Oslo')");
        jdbc.update("insert into zones (id) values ('z2')");

        assertThat(zones.infoOf("z1")).contains(new ZoneInfo("Storgatan 1", 90, "NO", ZoneId.of("Europe/Oslo")));
        assertThat(zones.infoOf("z2")).contains(ZoneInfo.unknown());
        assertThat(zones.infoOf("z9")).isEmpty();
    }

    @Test
    void zoneRulesAreMappedToTheDomainRule() {
        jdbc.update("""
                insert into zone_rules (zone_id, label, priority, vehicle_types, permit_required, weekdays, start_time, end_time,
                                        applies_holidays, max_minutes, no_parking)
                values ('z1', 'Night', 5, '{Truck, BUS}', 'resident', '{6,7}', '22:00', '06:00', false, 90, true)""");
        jdbc.update("insert into zone_rules (zone_id, label, enabled) values ('z1', 'Off', false)");

        List<ZoneRule> found = rules.enabledRulesOf("z1");

        assertThat(found).hasSize(1);
        ZoneRule rule = found.get(0);
        assertThat(rule.zoneId()).isEqualTo("z1");
        assertThat(rule.priority()).isEqualTo(5);
        assertThat(rule.vehicleTypes()).containsExactly("truck", "bus");
        assertThat(rule.permitRequired()).isEqualTo("resident");
        assertThat(rule.weekdays()).isEqualTo(Set.of(6, 7));
        assertThat(rule.start()).isEqualTo(LocalTime.of(22, 0));
        assertThat(rule.end()).isEqualTo(LocalTime.of(6, 0));
        assertThat(rule.appliesHolidays()).isFalse();
        assertThat(rule.maxMinutes()).isEqualTo(90);
        assertThat(rule.noParking()).isTrue();
        assertThat(rules.enabledRulesOf("nope")).isEmpty();
    }

    @Test
    void theStartOfTheActiveSessionIsReadPerPlateAndZone() {
        jdbc.update("insert into sessions (plate, zone_id, started_at) values ('ABC123', 'z1', '2026-10-12T09:00:00Z')");
        jdbc.update("insert into sessions (plate, zone_id, started_at, ended_at) values ('DEF456', 'z1', '2026-10-12T09:00:00Z', '2026-10-12T10:00:00Z')");

        assertThat(sessions.startOfActiveSession("ABC123", "z1")).contains(Instant.parse("2026-10-12T09:00:00Z"));
        assertThat(sessions.startOfActiveSession("ABC123", "z2")).isEmpty();
        assertThat(sessions.startOfActiveSession("DEF456", "z1")).isEmpty();
        assertThat(sessions.startOfActiveSession("NOPE11", "z1")).isEmpty();
    }
}
