package com.parkview.ruleengine.service;

import com.parkview.ruleengine.model.BoundaryCheckResult;
import com.parkview.ruleengine.model.ParkingSpotDto;
import com.parkview.ruleengine.model.ViolationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Boundary-check med Sutherland-Hodgman polygon clipping.
 * Beräknar IoU (Intersection over Union) och containment
 * utan externa geometribibliotek.
 */
@Slf4j
@Service
public class BoundaryService {

    @Value("${parkview.rule-engine.iou-threshold:0.85}")
    private double iouThreshold;

    @Value("${parkview.rule-engine.containment-threshold:0.90}")
    private double containmentThreshold;

    /**
     * Kontrollerar om ett fordon är korrekt placerat i en parkeringsspot.
     */
    public BoundaryCheckResult check(
            List<List<Double>> vehiclePolygon,
            ParkingSpotDto spot,
            String plate
    ) {
        var result = new BoundaryCheckResult();
        result.setSpotId(spot.getId().toString());
        result.setZoneId(spot.getZoneId());
        result.setPlate(plate);

        try {
            double intersection = polygonIntersectionArea(vehiclePolygon, spot.getPolygon());
            double carArea      = polygonArea(vehiclePolygon);
            double spotArea     = polygonArea(spot.getPolygon());
            double union        = carArea + spotArea - intersection;

            double iou         = union > 0 ? intersection / union : 0.0;
            double containment = carArea > 0 ? intersection / carArea : 0.0;

            result.setIou(round(iou));
            result.setContainment(round(containment));

            boolean occupied = iou >= iouThreshold || containment >= containmentThreshold;
            result.setValid(occupied);

            if (occupied) {
                ViolationType violation = evaluateViolation(iou, containment, spot, plate);
                result.setViolation(violation);
            }

        } catch (Exception e) {
            log.warn("Boundary-check misslyckades för spot {}: {}", spot.getId(), e.getMessage());
            result.setValid(false);
        }

        return result;
    }

    // ── Affärslogik för violations ─────────────────────────────────────────────

    private ViolationType evaluateViolation(
            double iou, double containment,
            ParkingSpotDto spot, String plate
    ) {
        // Gränsöverskridning: bilen sticker ut ur rutan
        if (iou < iouThreshold && containment >= containmentThreshold) {
            return ViolationType.BOUNDARY_EXCEEDED;
        }
        // Tillståndszone utan rätt tillstånd
        if ("permit".equals(spot.getType()) && spot.getPermitType() != null) {
            return ViolationType.WRONG_PERMIT;
        }
        // Parkeringsförbud
        if ("no_parking".equals(spot.getType())) {
            return ViolationType.NO_PARKING;
        }
        return null;
    }

    // ── Geometri: Sutherland-Hodgman + Shoelace ────────────────────────────────

    /**
     * Beräknar area av en polygon med Shoelace-formeln.
     */
    public double polygonArea(List<List<Double>> polygon) {
        if (polygon == null || polygon.size() < 3) return 0.0;
        int n = polygon.size();
        double area = 0.0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            area += polygon.get(i).get(0) * polygon.get(j).get(1);
            area -= polygon.get(j).get(0) * polygon.get(i).get(1);
        }
        return Math.abs(area) / 2.0;
    }

    /**
     * Beräknar intersection-area med Sutherland-Hodgman clipping.
     */
    public double polygonIntersectionArea(
            List<List<Double>> poly1,
            List<List<Double>> poly2
    ) {
        List<List<Double>> clipped = sutherlandHodgman(poly1, poly2);
        return polygonArea(clipped);
    }

    private List<List<Double>> sutherlandHodgman(
            List<List<Double>> subject,
            List<List<Double>> clip
    ) {
        List<List<Double>> output = new java.util.ArrayList<>(subject);
        if (output.isEmpty()) return output;

        int n = clip.size();
        for (int i = 0; i < n; i++) {
            if (output.isEmpty()) break;
            List<List<Double>> input = new java.util.ArrayList<>(output);
            output.clear();

            List<Double> edgeStart = clip.get(i);
            List<Double> edgeEnd   = clip.get((i + 1) % n);

            for (int j = 0; j < input.size(); j++) {
                List<Double> current  = input.get(j);
                List<Double> previous = input.get((j + input.size() - 1) % input.size());

                if (isInside(current, edgeStart, edgeEnd)) {
                    if (!isInside(previous, edgeStart, edgeEnd)) {
                        output.add(intersection(previous, current, edgeStart, edgeEnd));
                    }
                    output.add(current);
                } else if (isInside(previous, edgeStart, edgeEnd)) {
                    output.add(intersection(previous, current, edgeStart, edgeEnd));
                }
            }
        }
        return output;
    }

    private boolean isInside(List<Double> p, List<Double> a, List<Double> b) {
        return (b.get(0) - a.get(0)) * (p.get(1) - a.get(1))
                - (b.get(1) - a.get(1)) * (p.get(0) - a.get(0)) >= 0;
    }

    private List<Double> intersection(
            List<Double> a, List<Double> b,
            List<Double> c, List<Double> d
    ) {
        double a1 = b.get(1) - a.get(1), b1 = a.get(0) - b.get(0);
        double c1 = a1 * a.get(0) + b1 * a.get(1);
        double a2 = d.get(1) - c.get(1), b2 = c.get(0) - d.get(0);
        double c2 = a2 * c.get(0) + b2 * c.get(1);
        double det = a1 * b2 - a2 * b1;
        if (Math.abs(det) < 1e-10) return a;
        return List.of((b2 * c1 - b1 * c2) / det, (a1 * c2 - a2 * c1) / det);
    }

    private double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}