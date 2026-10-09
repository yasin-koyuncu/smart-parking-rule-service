package com.parkview.ruleengine.geometry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure planar geometry for the boundary check: area, polygon intersection (Sutherland-Hodgman),
 * IoU, containment and bounding boxes. No dependencies, no state.
 *
 * <p>Sutherland-Hodgman clips an arbitrary (even concave) <em>subject</em> polygon against a
 * <em>convex</em> clip polygon. Parking spots are convex (rectangles and quadrilaterals), so the spot
 * is always the clip polygon. The clip polygon is normalised to counter-clockwise order, so the
 * drawing direction of a spot does not matter.
 */
public final class PolygonGeometry {

    private static final double PARALLEL_EPSILON = 1e-12;

    private PolygonGeometry() {
    }

    /** Shoelace area; positive for counter-clockwise, negative for clockwise vertex order. */
    public static double signedArea(Polygon polygon) {
        return signedArea(polygon.vertices());
    }

    public static double area(Polygon polygon) {
        return Math.abs(signedArea(polygon.vertices()));
    }

    /** The same polygon in counter-clockwise vertex order. */
    public static Polygon counterClockwise(Polygon polygon) {
        if (signedArea(polygon.vertices()) >= 0) {
            return polygon;
        }
        List<Point> reversed = new ArrayList<>(polygon.vertices());
        Collections.reverse(reversed);
        return new Polygon(reversed);
    }

    public static BoundingBox boundingBox(Polygon polygon) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (Point p : polygon.vertices()) {
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
        }
        return new BoundingBox(minX, minY, maxX, maxY);
    }

    /**
     * Area of {@code subject} that lies inside the convex {@code clip}. Zero when they do not overlap
     * (or only touch).
     */
    public static double intersectionArea(Polygon subject, Polygon clip) {
        return Math.abs(signedArea(clip(subject.vertices(), counterClockwise(clip).vertices())));
    }

    /** Intersection over union, in [0, 1]. */
    public static double iou(Polygon subject, Polygon clip) {
        double intersection = intersectionArea(subject, clip);
        double union = area(subject) + area(clip) - intersection;
        return union > 0 ? intersection / union : 0.0;
    }

    /** Fraction of {@code subject}'s area that lies inside the convex {@code container}, in [0, 1]. */
    public static double containment(Polygon subject, Polygon container) {
        double subjectArea = area(subject);
        return subjectArea > 0 ? Math.min(1.0, intersectionArea(subject, container) / subjectArea) : 0.0;
    }

    private static double signedArea(List<Point> vertices) {
        if (vertices.size() < Polygon.MIN_VERTICES) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 0; i < vertices.size(); i++) {
            Point a = vertices.get(i);
            Point b = vertices.get((i + 1) % vertices.size());
            sum += a.x() * b.y() - b.x() * a.y();
        }
        return sum / 2.0;
    }

    private static List<Point> clip(List<Point> subject, List<Point> counterClockwiseClip) {
        List<Point> output = new ArrayList<>(subject);
        int n = counterClockwiseClip.size();
        for (int i = 0; i < n && !output.isEmpty(); i++) {
            List<Point> input = output;
            output = new ArrayList<>();
            Point edgeStart = counterClockwiseClip.get(i);
            Point edgeEnd = counterClockwiseClip.get((i + 1) % n);

            for (int j = 0; j < input.size(); j++) {
                Point current = input.get(j);
                Point previous = input.get((j + input.size() - 1) % input.size());
                boolean currentInside = isInside(current, edgeStart, edgeEnd);
                boolean previousInside = isInside(previous, edgeStart, edgeEnd);
                if (currentInside) {
                    if (!previousInside) {
                        output.add(lineIntersection(previous, current, edgeStart, edgeEnd));
                    }
                    output.add(current);
                } else if (previousInside) {
                    output.add(lineIntersection(previous, current, edgeStart, edgeEnd));
                }
            }
        }
        return output;
    }

    /** Left of (or on) the directed edge a to b: "inside" for a counter-clockwise clip polygon. */
    private static boolean isInside(Point p, Point a, Point b) {
        return (b.x() - a.x()) * (p.y() - a.y()) - (b.y() - a.y()) * (p.x() - a.x()) >= 0;
    }

    /** Intersection of the infinite lines a-b and c-d. */
    private static Point lineIntersection(Point a, Point b, Point c, Point d) {
        double a1 = b.y() - a.y();
        double b1 = a.x() - b.x();
        double c1 = a1 * a.x() + b1 * a.y();
        double a2 = d.y() - c.y();
        double b2 = c.x() - d.x();
        double c2 = a2 * c.x() + b2 * c.y();
        double det = a1 * b2 - a2 * b1;
        if (Math.abs(det) < PARALLEL_EPSILON) {
            return a;
        }
        return new Point((b2 * c1 - b1 * c2) / det, (a1 * c2 - a2 * c1) / det);
    }
}
