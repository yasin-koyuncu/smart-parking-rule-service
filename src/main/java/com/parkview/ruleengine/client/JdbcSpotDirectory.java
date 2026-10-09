package com.parkview.ruleengine.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkview.ruleengine.domain.ParkingSpot;
import com.parkview.ruleengine.geometry.Polygon;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Reads polygon spots. {@code coordinates} is jsonb; Postgres normalises it to {@code [[0, 0], [0, 10]]}, so it is parsed as real JSON. */
@Slf4j
@Repository
@RequiredArgsConstructor
class JdbcSpotDirectory implements SpotDirectory {

    private static final TypeReference<List<List<Double>>> COORDINATES = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Override
    public List<ParkingSpot> polygonSpotsOf(String zoneId) {
        return jdbc.query("""
                        select id, zone_id, spot_number, type, permit_type, coordinates,
                               boundary_grace_min, permit_grace_min
                        from parking_spots
                        where zone_id = ? and shape = 'polygon'
                        """, this::mapRow, zoneId)
                .stream().filter(Objects::nonNull).toList();
    }

    /** @return the spot, or null (skipped) when its coordinates are not a usable polygon */
    private ParkingSpot mapRow(ResultSet rs, int rowNum) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        Polygon polygon;
        try {
            polygon = Polygon.of(objectMapper.readValue(rs.getString("coordinates"), COORDINATES));
        } catch (Exception e) {
            log.warn("Skipping spot {}: invalid polygon coordinates ({})", id, e.getMessage());
            return null;
        }
        return new ParkingSpot(id, rs.getString("zone_id"), rs.getString("spot_number"),
                rs.getString("type"), rs.getString("permit_type"), polygon,
                rs.getInt("boundary_grace_min"), rs.getInt("permit_grace_min"));
    }
}
