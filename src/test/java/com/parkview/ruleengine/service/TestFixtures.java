package com.parkview.ruleengine.service;

import com.parkview.ruleengine.config.RuleEngineProperties;
import com.parkview.ruleengine.domain.ParkingSpot;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.ViolationType;
import com.parkview.ruleengine.geometry.Point;
import com.parkview.ruleengine.geometry.Polygon;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class TestFixtures {

    static final String ZONE = "zone-1";
    static final String PLATE = "ABC123";

    private TestFixtures() {
    }

    static RuleEngineProperties properties() {
        return new RuleEngineProperties(0.85, 0.90, 0.5, 10, 30,
                BigDecimal.valueOf(900), BigDecimal.valueOf(450), BigDecimal.valueOf(700), BigDecimal.valueOf(900),
                60000, 20, 15, 60000, 30, 5);
    }

    static Polygon rect(double x0, double y0, double x1, double y1) {
        return new Polygon(List.of(new Point(x0, y0), new Point(x1, y0), new Point(x1, y1), new Point(x0, y1)));
    }

    static ParkingSpot spot(String type, String permitType, int boundaryGrace, int permitGrace) {
        return new ParkingSpot(UUID.randomUUID(), ZONE, "A1", type, permitType, rect(0, 0, 10, 10), boundaryGrace, permitGrace);
    }

    static Violation violation(ViolationType type, Instant detectedAt, Instant graceUntil) {
        Violation v = Violation.open(UUID.randomUUID(), ZONE, PLATE, null, type, detectedAt, graceUntil);
        ReflectionTestUtils.setField(v, "id", UUID.randomUUID());
        return v;
    }
}
