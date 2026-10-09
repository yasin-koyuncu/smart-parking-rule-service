package com.parkview.ruleengine.geometry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PolygonGeometryTest {

    private static final double EPS = 1e-9;

    /** Axis-aligned rectangle, counter-clockwise. */
    private static Polygon rect(double x0, double y0, double x1, double y1) {
        return new Polygon(List.of(new Point(x0, y0), new Point(x1, y0), new Point(x1, y1), new Point(x0, y1)));
    }

    private static Polygon clockwise(Polygon p) {
        List<Point> reversed = new ArrayList<>(p.vertices());
        Collections.reverse(reversed);
        return new Polygon(reversed);
    }

    private static Polygon triangle(double ax, double ay, double bx, double by, double cx, double cy) {
        return new Polygon(List.of(new Point(ax, ay), new Point(bx, by), new Point(cx, cy)));
    }

    @Test
    void areaIsIndependentOfWindingAndSignedAreaShowsIt() {
        Polygon ccw = rect(0, 0, 4, 3);
        Polygon cw = clockwise(ccw);

        assertThat(PolygonGeometry.area(ccw)).isEqualTo(12.0, within(EPS));
        assertThat(PolygonGeometry.area(cw)).isEqualTo(12.0, within(EPS));
        assertThat(PolygonGeometry.signedArea(ccw)).isEqualTo(12.0, within(EPS));
        assertThat(PolygonGeometry.signedArea(cw)).isEqualTo(-12.0, within(EPS));
    }

    @Test
    void counterClockwiseNormalisesOnlyClockwiseInput() {
        Polygon ccw = rect(0, 0, 1, 1);

        assertThat(PolygonGeometry.counterClockwise(ccw)).isSameAs(ccw);
        assertThat(PolygonGeometry.signedArea(PolygonGeometry.counterClockwise(clockwise(ccw)))).isPositive();
    }

    @Test
    void areaOfTriangle() {
        assertThat(PolygonGeometry.area(triangle(0, 0, 4, 0, 0, 3))).isEqualTo(6.0, within(EPS));
    }

    static Stream<Arguments> intersectionCases() {
        Polygon spot = rect(0, 0, 10, 10);
        return Stream.of(
                Arguments.of("identical", rect(0, 0, 10, 10), spot, 100.0),
                Arguments.of("fully inside", rect(2, 2, 6, 6), spot, 16.0),
                Arguments.of("spot fully inside subject", rect(-5, -5, 20, 20), spot, 100.0),
                Arguments.of("half overlap", rect(5, 0, 15, 10), spot, 50.0),
                Arguments.of("corner overlap", rect(8, 8, 12, 12), spot, 4.0),
                Arguments.of("disjoint", rect(20, 20, 30, 30), spot, 0.0),
                Arguments.of("touching edge only", rect(10, 0, 20, 10), spot, 0.0),
                Arguments.of("triangle cutting a corner", triangle(0, 0, 4, 0, 0, 4), spot, 8.0)
        );
    }

    @ParameterizedTest(name = "{0}: subject ccw, clip ccw")
    @MethodSource("intersectionCases")
    void intersectionAreaWithCounterClockwiseClip(String name, Polygon subject, Polygon clip, double expected) {
        assertThat(PolygonGeometry.intersectionArea(subject, clip)).isEqualTo(expected, within(EPS));
    }

    @ParameterizedTest(name = "{0}: clip clockwise")
    @MethodSource("intersectionCases")
    void intersectionAreaWithClockwiseClip(String name, Polygon subject, Polygon clip, double expected) {
        assertThat(PolygonGeometry.intersectionArea(subject, clockwise(clip))).isEqualTo(expected, within(EPS));
    }

    @ParameterizedTest(name = "{0}: subject clockwise, clip clockwise")
    @MethodSource("intersectionCases")
    void intersectionAreaWithBothClockwise(String name, Polygon subject, Polygon clip, double expected) {
        assertThat(PolygonGeometry.intersectionArea(clockwise(subject), clockwise(clip))).isEqualTo(expected, within(EPS));
    }

    @Test
    void intersectionIsSymmetricForConvexPolygons() {
        Polygon a = rect(0, 0, 10, 10);
        Polygon b = triangle(5, -5, 15, 5, 5, 15);

        assertThat(PolygonGeometry.intersectionArea(a, b))
                .isEqualTo(PolygonGeometry.intersectionArea(b, a), within(1e-6));
    }

    @Test
    void concaveSubjectIsClippedAgainstConvexClip() {
        // L-shape of area 3 (unit squares at (0,0), (1,0), (0,1)), clipped by the unit square at the origin
        Polygon lShape = new Polygon(List.of(new Point(0, 0), new Point(2, 0), new Point(2, 1),
                new Point(1, 1), new Point(1, 2), new Point(0, 2)));

        assertThat(PolygonGeometry.area(lShape)).isEqualTo(3.0, within(EPS));
        assertThat(PolygonGeometry.intersectionArea(lShape, rect(0, 0, 1, 1))).isEqualTo(1.0, within(EPS));
    }

    @Test
    void iouOfKnownConfigurations() {
        Polygon spot = rect(0, 0, 10, 10);

        assertThat(PolygonGeometry.iou(rect(0, 0, 10, 10), spot)).isEqualTo(1.0, within(EPS));
        assertThat(PolygonGeometry.iou(rect(5, 0, 15, 10), spot)).isEqualTo(50.0 / 150.0, within(EPS));
        assertThat(PolygonGeometry.iou(rect(2, 2, 6, 6), spot)).isEqualTo(16.0 / 100.0, within(EPS));
        assertThat(PolygonGeometry.iou(rect(20, 20, 30, 30), spot)).isZero();
        assertThat(PolygonGeometry.iou(rect(0, 0, 10, 10), clockwise(spot))).isEqualTo(1.0, within(EPS));
    }

    @Test
    void containmentIsTheShareOfTheSubjectInsideTheContainer() {
        Polygon spot = rect(0, 0, 10, 10);

        assertThat(PolygonGeometry.containment(rect(2, 2, 6, 6), spot)).isEqualTo(1.0, within(EPS));
        assertThat(PolygonGeometry.containment(rect(5, 0, 15, 10), spot)).isEqualTo(0.5, within(EPS));
        assertThat(PolygonGeometry.containment(rect(5, 0, 15, 10), clockwise(spot))).isEqualTo(0.5, within(EPS));
        assertThat(PolygonGeometry.containment(rect(20, 20, 30, 30), spot)).isZero();
    }

    @Test
    void boundingBoxCoversAllVertices() {
        BoundingBox box = PolygonGeometry.boundingBox(triangle(-1, 2, 5, -3, 4, 7));

        assertThat(box).isEqualTo(new BoundingBox(-1, -3, 5, 7));
    }

    static Stream<Arguments> boxCases() {
        BoundingBox base = new BoundingBox(0, 0, 10, 10);
        return Stream.of(
                Arguments.of(new BoundingBox(5, 5, 15, 15), true),
                Arguments.of(new BoundingBox(2, 2, 3, 3), true),
                Arguments.of(new BoundingBox(10, 0, 20, 10), true),
                Arguments.of(new BoundingBox(11, 0, 20, 10), false),
                Arguments.of(new BoundingBox(0, 11, 10, 20), false),
                Arguments.of(new BoundingBox(-20, -20, -1, -1), false)
        ).map(a -> Arguments.of(base, a.get()[0], a.get()[1]));
    }

    @ParameterizedTest
    @MethodSource("boxCases")
    void boundingBoxOverlap(BoundingBox a, BoundingBox b, boolean expected) {
        assertThat(a.overlaps(b)).isEqualTo(expected);
        assertThat(b.overlaps(a)).isEqualTo(expected);
    }

    @Test
    void polygonFromWireFormat() {
        Polygon p = Polygon.of(List.of(List.of(0.0, 0.0), List.of(0.0, 10.0), List.of(10.0, 10.0), List.of(10.0, 0.0)));

        assertThat(PolygonGeometry.area(p)).isEqualTo(100.0, within(EPS));
        assertThat(PolygonGeometry.signedArea(p)).isNegative();
    }

    @Test
    void invalidPolygonsAreRejected() {
        assertThatThrownBy(() -> Polygon.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Polygon.of(List.of(List.of(0.0, 0.0), List.of(1.0, 1.0))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Polygon.of(List.of(List.of(0.0, 0.0), List.of(1.0), List.of(1.0, 1.0))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Point(Double.NaN, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void degeneratePolygonHasZeroAreaAndNoOverlap() {
        Polygon line = triangle(0, 0, 5, 5, 10, 10);

        assertThat(PolygonGeometry.area(line)).isZero();
        assertThat(PolygonGeometry.containment(line, rect(0, 0, 10, 10))).isZero();
        assertThat(PolygonGeometry.iou(line, line)).isZero();
    }
}
