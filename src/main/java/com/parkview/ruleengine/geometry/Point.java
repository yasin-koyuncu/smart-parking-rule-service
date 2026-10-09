package com.parkview.ruleengine.geometry;

/**
 * A 2D coordinate. The unit is whatever the producer uses (map coordinates), but both operands of a
 * comparison must share it.
 */
public record Point(double x, double y) {

    public Point {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("Coordinates must be finite numbers");
        }
    }
}
