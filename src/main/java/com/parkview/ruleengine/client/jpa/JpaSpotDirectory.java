package com.parkview.ruleengine.client.jpa;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkview.ruleengine.client.SpotDirectory;
import com.parkview.ruleengine.domain.value.ParkingSpot;
import com.parkview.ruleengine.geometry.Polygon;
import com.parkview.ruleengine.readmodel.ParkingSpotView;
import com.parkview.ruleengine.readmodel.ParkingSpotViewRepository;
import com.parkview.ruleengine.readmodel.PlacedSpot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads polygon spots. {@code coordinates} and {@code image_polygon} are jsonb; Postgres normalises them to
 * {@code [[0, 0], [0, 10]]}, so they are parsed as real JSON.
 *
 * <p>A database without the camera-view columns (see {@link SpotViewSchema}) is read as before views existed: every
 * polygon spot is a legacy spot and there are no view spots.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class JpaSpotDirectory implements SpotDirectory {

    private static final TypeReference<List<List<Double>>> COORDINATES = new TypeReference<>() {
    };

    private final ParkingSpotViewRepository spots;
    private final ObjectMapper objectMapper;
    private final SpotViewSchema schema;

    @Override
    public List<ParkingSpot> polygonSpotsOf(String zoneId) {
        List<ParkingSpotView> found = schema.present()
                ? spots.findPolygonSpotsOutsideViews(zoneId)
                : spots.findPolygonSpotsByZoneId(zoneId);
        return found.stream()
                .map(view -> toSpot(view, view.getCoordinates()))
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<ParkingSpot> polygonSpotsOfView(String zoneId, UUID viewId) {
        if (!schema.present()) {
            log.debug("View {} asked for before parking_spots has camera-view columns: no spots", viewId);
            return List.of();
        }
        return spots.findSpotsOnView(viewId, zoneId).stream()
                .map((PlacedSpot placed) -> toSpot(placed.spot(), placed.imagePolygon()))
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public Optional<String> spotNumberOf(UUID spotId) {
        return spots.findById(spotId).map(ParkingSpotView::getSpotNumber);
    }

    /**
     * @param polygonJson the raw polygon: {@code coordinates} of a legacy spot, {@code image_polygon} of a view spot
     * @return the spot, or null (skipped) when it has no usable polygon; a null polygon is normal for a spot that is
     * only a geo polygon, or not drawn on its view yet
     */
    private ParkingSpot toSpot(ParkingSpotView view, String polygonJson) {
        if (polygonJson == null) {
            log.debug("Skipping spot {}: no polygon", view.getId());
            return null;
        }
        Polygon polygon;
        try {
            polygon = Polygon.of(objectMapper.readValue(polygonJson, COORDINATES));
        } catch (Exception e) {
            log.warn("Skipping spot {}: invalid polygon coordinates ({})", view.getId(), e.getMessage());
            return null;
        }
        return new ParkingSpot(view.getId(), view.getZoneId(), view.getSpotNumber(),
                view.getType(), view.getPermitType(), polygon,
                view.getBoundaryGraceMin(), view.getPermitGraceMin(), orZero(view.getMaxMinutes()));
    }

    /** A missing stay limit reads as zero, which the rules treat as "none". A missing grace stays null: "use the default". */
    private static int orZero(Integer minutes) {
        return minutes == null ? 0 : minutes;
    }
}
