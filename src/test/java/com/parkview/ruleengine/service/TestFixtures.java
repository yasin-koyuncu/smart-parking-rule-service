package com.parkview.ruleengine.service;

import com.parkview.ruleengine.config.RuleEngineProperties;
import com.parkview.ruleengine.domain.value.ParkingSpot;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.type.ViolationType;
import com.parkview.ruleengine.geometry.Point;
import com.parkview.ruleengine.geometry.Polygon;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TestFixtures {

    public static final String ZONE = "zone-1";
    public static final String PLATE = "ABC123";

    private TestFixtures() {
    }

    /** The defaults: fine amounts for SEK only. */
    public static RuleEngineProperties properties() {
        return properties(Map.of());
    }

    /** The defaults, plus the given fine amounts per currency (SEK keeps its defaults unless it is listed). */
    public static RuleEngineProperties properties(Map<String, Map<ViolationType, BigDecimal>> fineAmounts) {
        return new RuleEngineProperties(0.85, 0.90, 0.5, 10, 30, 5, 5, 10,
                fineAmounts, 60000, 20, 15, 120, 60000, 30, 5, 15, 600, 6, 60, true);
    }

    public static Polygon rect(double x0, double y0, double x1, double y1) {
        return new Polygon(List.of(new Point(x0, y0), new Point(x1, y0), new Point(x1, y1), new Point(x0, y1)));
    }

    public static ParkingSpot spot(String type, String permitType, Integer boundaryGrace, Integer permitGrace) {
        return new ParkingSpot(UUID.randomUUID(), ZONE, ZONE, "A1", type, permitType, rect(0, 0, 10, 10), boundaryGrace, permitGrace, 0);
    }

    public static Violation violation(ViolationType type, Instant detectedAt, Instant graceUntil) {
        Violation v = Violation.open(UUID.randomUUID(), ZONE, ZONE, PLATE, null, type, detectedAt, graceUntil);
        ReflectionTestUtils.setField(v, "id", UUID.randomUUID());
        return v;
    }
}
