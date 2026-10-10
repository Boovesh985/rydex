package com.rydex.common.geo;

/** WGS84 point with an optional human-readable address. */
public record GeoPoint(double lat, double lng, String address) {

    private static final double EARTH_RADIUS_M = 6_371_000;

    public GeoPoint {
        if (lat < -90 || lat > 90) {
            throw new IllegalArgumentException("lat out of range: " + lat);
        }
        if (lng < -180 || lng > 180) {
            throw new IllegalArgumentException("lng out of range: " + lng);
        }
    }

    public static GeoPoint of(double lat, double lng) {
        return new GeoPoint(lat, lng, null);
    }

    /** Great-circle (haversine) distance in metres. */
    public double distanceMetersTo(GeoPoint other) {
        double dLat = Math.toRadians(other.lat - lat);
        double dLng = Math.toRadians(other.lng - lng);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(other.lat))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a));
    }
}
