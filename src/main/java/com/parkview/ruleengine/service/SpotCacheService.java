package com.parkview.ruleengine.service;

import com.parkview.ruleengine.model.ParkingSpotDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cacchar parkeringsspot-polygoner i minnet.
 * Laddar om från databasen var 5:e minut.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpotCacheService {

    private final JdbcTemplate jdbc;

    private final Map<String, List<ParkingSpotDto>> cache    = new ConcurrentHashMap<>();
    private final Map<String, Long>                 loadedAt = new ConcurrentHashMap<>();
    private static final long TTL_MS = 5 * 60 * 1000L;

    public List<ParkingSpotDto> getSpotsForZone(String zoneId) {
        long now = System.currentTimeMillis();
        if (!cache.containsKey(zoneId) || now - loadedAt.getOrDefault(zoneId, 0L) > TTL_MS) {
            loadFromDb(zoneId);
        }
        return cache.getOrDefault(zoneId, List.of());
    }

    private void loadFromDb(String zoneId) {
        try {
            var spots = jdbc.query("""
                SELECT id, zone_id, spot_number, type, permit_type,
                       coordinates, boundary_grace_min, permit_grace_min
                FROM parking_spots
                WHERE zone_id = ? AND shape = 'polygon'
                """,
                    (rs, i) -> {
                        var dto = new ParkingSpotDto();
                        dto.setId(UUID.fromString(rs.getString("id")));
                        dto.setZoneId(rs.getString("zone_id"));
                        dto.setSpotNumber(rs.getString("spot_number"));
                        dto.setType(rs.getString("type"));
                        dto.setPermitType(rs.getString("permit_type"));
                        dto.setBoundaryGraceMin(rs.getInt("boundary_grace_min"));
                        dto.setPermitGraceMin(rs.getInt("permit_grace_min"));
                        // Parsa jsonb coordinates till List<List<Double>>
                        dto.setPolygon(parseCoordinates(rs.getString("coordinates")));
                        return dto;
                    },
                    zoneId
            );
            cache.put(zoneId, spots);
            loadedAt.put(zoneId, System.currentTimeMillis());
            log.info("Laddade {} spots för zon {} från DB", spots.size(), zoneId);
        } catch (Exception e) {
            log.error("Kunde inte ladda spots för zon {}: {}", zoneId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<List<Double>> parseCoordinates(String json) {
        // Enkel JSON-parsning av [[x,y],[x,y],...] utan extern lib
        var result = new ArrayList<List<Double>>();
        if (json == null || json.isBlank()) return result;
        json = json.trim().replaceAll("^\\[\\[", "").replaceAll("\\]\\]$", "");
        for (String pair : json.split("\\],\\[")) {
            var parts = pair.replace("[","").replace("]","").split(",");
            if (parts.length >= 2) {
                result.add(List.of(
                        Double.parseDouble(parts[0].trim()),
                        Double.parseDouble(parts[1].trim())
                ));
            }
        }
        return result;
    }

    public void invalidate(String zoneId) {
        cache.remove(zoneId);
        loadedAt.remove(zoneId);
    }
}