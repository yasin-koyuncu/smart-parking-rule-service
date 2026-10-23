package com.parkview.ruleengine.geometry;

import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.util.GeometryFixer;

/**
 * How much two polygons overlap. This is what the boundary check needs to know about a vehicle and a parking spot:
 * <ul>
 *   <li>{@link #area}: the size of one polygon;</li>
 *   <li>{@link #intersectionArea}: the size of the part they share;</li>
 *   <li>{@link #iou}: shared area divided by the area of both together (1 = identical, 0 = apart);</li>
 *   <li>{@link #containment}: how much of the first polygon lies inside the second (1 = all of it).</li>
 * </ul>
 *
 * <p>The geometry is done by <a href="https://locationtech.github.io/jts/">JTS</a>, the standard Java geometry library, so
 * there is no polygon clipping to maintain here. Winding order (clockwise or not) does not matter, and a polygon whose
 * edges cross or that has no area is repaired or counts as empty instead of failing.
 */
public final class PolygonGeometry {

    private static final GeometryFactory FACTORY = new GeometryFactory();

    private PolygonGeometry() {
    }

    /** The size of the polygon; 0 when it has no area. */
    public static double area(Polygon polygon) {
        return shape(polygon).getArea();
    }

    /** The smallest axis-aligned box around the polygon, used as a cheap test before the exact overlap is calculated. */
    public static BoundingBox boundingBox(Polygon polygon) {
        Envelope box = new Envelope();
        for (Point p : polygon.vertices()) {
            box.expandToInclude(p.x(), p.y());
        }
        return new BoundingBox(box.getMinX(), box.getMinY(), box.getMaxX(), box.getMaxY());
    }

    /** The size of the part that both polygons cover; 0 when they are apart or only touch. */
    public static double intersectionArea(Polygon first, Polygon second) {
        return shape(first).intersection(shape(second)).getArea();
    }

    /** Intersection over union, in [0, 1]. */
    public static double iou(Polygon first, Polygon second) {
        double shared = intersectionArea(first, second);
        double union = area(first) + area(second) - shared;
        return union > 0 ? shared / union : 0.0;
    }

    /** The share of {@code subject} that lies inside {@code container}, in [0, 1]. */
    public static double containment(Polygon subject, Polygon container) {
        double subjectArea = area(subject);
        return subjectArea > 0 ? Math.min(1.0, intersectionArea(subject, container) / subjectArea) : 0.0;
    }

    /** The polygon as a JTS shape, repaired when its edges cross (JTS cannot calculate with an invalid one). */
    private static Geometry shape(Polygon polygon) {
        List<Point> vertices = polygon.vertices();
        Coordinate[] ring = new Coordinate[vertices.size() + 1];
        for (int i = 0; i < vertices.size(); i++) {
            ring[i] = new Coordinate(vertices.get(i).x(), vertices.get(i).y());
        }
        ring[vertices.size()] = ring[0];                       // a JTS ring is closed
        Geometry shape = FACTORY.createPolygon(ring);
        return shape.isValid() ? shape : GeometryFixer.fix(shape);
    }
}
