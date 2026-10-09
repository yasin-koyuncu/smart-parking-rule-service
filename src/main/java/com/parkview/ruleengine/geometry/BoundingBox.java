package com.parkview.ruleengine.geometry;

/** Axis-aligned bounding box, used as a cheap pre-filter before the exact polygon clipping. */
public record BoundingBox(double minX, double minY, double maxX, double maxY) {

    /** True when the two boxes share at least one point (touching edges count as overlapping). */
    public boolean overlaps(BoundingBox other) {
        return minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY;
    }
}
