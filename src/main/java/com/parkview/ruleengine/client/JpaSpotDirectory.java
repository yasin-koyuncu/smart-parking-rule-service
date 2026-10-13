package com.parkview.ruleengine.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkview.ruleengine.domain.ParkingSpot;
import com.parkview.ruleengine.geometry.Polygon;
import com.parkview.ruleengine.readmodel.ParkingSpotView;
import com.parkview.ruleengine.readmodel.ParkingSpotViewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** Reads polygon spots. {@code coordinates} is jsonb; Postgres normalises it to {@code [[0, 0], [0, 10]]}, so it is parsed as real JSON. */
@Slf4j
@Component
@RequiredArgsConstructor
class JpaSpotDirectory implements SpotDirectory {

    private static final TypeReference<List<List<Double>>> COORDINATES = new TypeReference<>() {
    };

    private final ParkingSpotViewRepository spots;
    private final ObjectMapper objectMapper;

    @Override
    public List<ParkingSpot> polygonSpotsOf(String zoneId) {
        return spots.findPolygonSpotsByZoneId(zoneId).stream()
                .map(this::toSpot)
                .filter(Objects::nonNull)
                .toList();
    }

    /** @return the spot, or null (skipped) when its coordinates are not a usable polygon */
    private ParkingSpot toSpot(ParkingSpotView view) {
        Polygon polygon;
        try {
            polygon = Polygon.of(objectMapper.readValue(view.getCoordinates(), COORDINATES));
        } catch (Exception e) {
            log.warn("Skipping spot {}: invalid polygon coordinates ({})", view.getId(), e.getMessage());
            return null;
        }
        return new ParkingSpot(view.getId(), view.getZoneId(), view.getSpotNumber(),
                view.getType(), view.getPermitType(), polygon,
                orZero(view.getBoundaryGraceMin()), orZero(view.getPermitGraceMin()), orZero(view.getMaxMinutes()));
    }

    /** A missing grace or limit reads as zero, which the rules treat as "use the default" / "none". */
    private static int orZero(Integer minutes) {
        return minutes == null ? 0 : minutes;
    }
}
