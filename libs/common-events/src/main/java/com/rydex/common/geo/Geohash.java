package com.rydex.common.geo;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal geohash encoder/decoder. Precision-6 cells are about 1.2 km x 0.6 km,
 * so the 3x3 block around a rider covers roughly 3.6 km x 1.8 km.
 */
public final class Geohash {

    public static final int MATCHING_PRECISION = 6;
    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";
    private static final int[][] NEIGHBOUR_OFFSETS = {
        {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
    };

    private Geohash() {
    }

    public static String encode(GeoPoint p, int precision) {
        return encode(p.lat(), p.lng(), precision);
    }

    public static String encode(double lat, double lng, int precision) {
        if (precision < 1 || precision > 12) {
            throw new IllegalArgumentException("precision must be 1..12");
        }
        double[] latRange = {-90, 90};
        double[] lngRange = {-180, 180};
        StringBuilder hash = new StringBuilder(precision);
        boolean evenBit = true;
        int bit = 0;
        int ch = 0;
        while (hash.length() < precision) {
            double[] range = evenBit ? lngRange : latRange;
            double value = evenBit ? lng : lat;
            double mid = (range[0] + range[1]) / 2;
            if (value >= mid) {
                ch = (ch << 1) | 1;
                range[0] = mid;
            } else {
                ch = ch << 1;
                range[1] = mid;
            }
            evenBit = !evenBit;
            if (++bit == 5) {
                hash.append(BASE32.charAt(ch));
                bit = 0;
                ch = 0;
            }
        }
        return hash.toString();
    }

    /** Bounding box of a cell as {minLat, minLng, maxLat, maxLng}. */
    public static double[] bounds(String geohash) {
        double[] latRange = {-90, 90};
        double[] lngRange = {-180, 180};
        boolean evenBit = true;
        for (char c : geohash.toLowerCase().toCharArray()) {
            int idx = BASE32.indexOf(c);
            if (idx < 0) {
                throw new IllegalArgumentException("invalid geohash: " + geohash);
            }
            for (int n = 4; n >= 0; n--) {
                double[] range = evenBit ? lngRange : latRange;
                double mid = (range[0] + range[1]) / 2;
                if (((idx >> n) & 1) == 1) {
                    range[0] = mid;
                } else {
                    range[1] = mid;
                }
                evenBit = !evenBit;
            }
        }
        return new double[] {latRange[0], lngRange[0], latRange[1], lngRange[1]};
    }

    public static GeoPoint center(String geohash) {
        double[] b = bounds(geohash);
        return GeoPoint.of((b[0] + b[2]) / 2, (b[1] + b[3]) / 2);
    }

    /** The 8 surrounding cells in order N, NE, E, SE, S, SW, W, NW. */
    public static List<String> neighbours(String geohash) {
        double[] b = bounds(geohash);
        double h = b[2] - b[0];
        double w = b[3] - b[1];
        double lat = (b[0] + b[2]) / 2;
        double lng = (b[1] + b[3]) / 2;
        List<String> result = new ArrayList<>(8);
        for (int[] o : NEIGHBOUR_OFFSETS) {
            double nLat = Math.max(-89.999999, Math.min(89.999999, lat + o[0] * h));
            result.add(encode(nLat, wrapLng(lng + o[1] * w), geohash.length()));
        }
        return result;
    }

    /** Centre cell followed by its 8 neighbours: the 3x3 search area. */
    public static List<String> grid3x3(String geohash) {
        List<String> cells = new ArrayList<>(9);
        cells.add(geohash);
        cells.addAll(neighbours(geohash));
        return cells;
    }

    private static double wrapLng(double lng) {
        if (lng >= 180) {
            return lng - 360;
        }
        if (lng < -180) {
            return lng + 360;
        }
        return lng;
    }
}
