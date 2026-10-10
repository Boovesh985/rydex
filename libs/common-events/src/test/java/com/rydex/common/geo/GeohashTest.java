package com.rydex.common.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class GeohashTest {

    @Test
    void encodesKnownReferencePoints() {
        assertThat(Geohash.encode(57.64911, 10.40744, 11)).isEqualTo("u4pruydqqvj");
        assertThat(Geohash.encode(42.605, -5.603, 5)).isEqualTo("ezs42");
    }

    @Test
    void decodedCentreFallsInsideOriginalCell() {
        String cell = Geohash.encode(13.0827, 80.2707, 6);
        GeoPoint c = Geohash.center(cell);

        assertThat(Geohash.encode(c, 6)).isEqualTo(cell);
        assertThat(c.lat()).isCloseTo(13.0827, within(0.01));
        assertThat(c.lng()).isCloseTo(80.2707, within(0.01));
    }

    @Test
    void hasEightDistinctNeighboursThatAreSymmetric() {
        String cell = Geohash.encode(13.0827, 80.2707, 6);
        var neighbours = Geohash.neighbours(cell);

        assertThat(neighbours).hasSize(8).doesNotHaveDuplicates().doesNotContain(cell);
        for (String n : neighbours) {
            assertThat(Geohash.neighbours(n)).contains(cell);
        }
    }

    @Test
    void neighboursAreCompassOrdered() {
        String cell = "9q8x2";
        var n = Geohash.neighbours(cell);
        GeoPoint c = Geohash.center(cell);

        assertThat(Geohash.center(n.get(0)).lat()).isGreaterThan(c.lat()); // N
        assertThat(Geohash.center(n.get(2)).lng()).isGreaterThan(c.lng()); // E
        assertThat(Geohash.center(n.get(4)).lat()).isLessThan(c.lat()); // S
        assertThat(Geohash.center(n.get(6)).lng()).isLessThan(c.lng()); // W
    }

    @Test
    void wrapsAcrossTheAntimeridian() {
        String east = Geohash.encode(0.1, 179.99, 4);

        assertThat(Geohash.neighbours(east).get(2)).isEqualTo(Geohash.encode(0.1, -179.99, 4));
    }

    @Test
    void gridIsCentrePlusNeighbours() {
        assertThat(Geohash.grid3x3("tf2bq0")).hasSize(9).startsWith("tf2bq0").doesNotHaveDuplicates();
    }

    @Test
    void haversineDistance() {
        // Chennai Central -> Chennai airport is ~14.5 km in a straight line
        double d = GeoPoint.of(13.0827, 80.2707).distanceMetersTo(GeoPoint.of(12.9941, 80.1709));

        assertThat(d).isBetween(14_000.0, 15_000.0);
    }
}
