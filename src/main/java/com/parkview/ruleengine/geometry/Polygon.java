package com.parkview.ruleengine.geometry;

import java.util.List;

/**
 * An immutable simple polygon given by its vertices (the closing edge is implicit).
 * Either winding order is accepted; {@link PolygonGeometry} normalises where it matters.
 */
public record Polygon(List<Point> vertices) {

    public static final int MIN_VERTICES = 3;

    public Polygon {
        if (vertices == null || vertices.size() < MIN_VERTICES) {
            throw new IllegalArgumentException("A polygon needs at least " + MIN_VERTICES + " vertices");
        }
        vertices = List.copyOf(vertices);
    }

    /**
     * Builds a polygon from the wire format {@code [[x, y], ...]}.
     *
     * @throws IllegalArgumentException when a coordinate pair is malformed or fewer than three vertices are given
     */
    public static Polygon of(List<List<Double>> coordinates) {
        if (coordinates == null) {
            throw new IllegalArgumentException("Coordinates are missing");
        }
        List<Point> points = coordinates.stream().map(pair -> {
            if (pair == null || pair.size() != 2 || pair.get(0) == null || pair.get(1) == null) {
                throw new IllegalArgumentException("Every coordinate must be an [x, y] pair");
            }
            return new Point(pair.get(0), pair.get(1));
        }).toList();
        return new Polygon(points);
    }
}
