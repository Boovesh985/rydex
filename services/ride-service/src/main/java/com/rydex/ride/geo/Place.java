package com.rydex.ride.geo;

/** A geocoded place. {@code name} is short ("Chennai Central"), {@code address} the full line. */
public record Place(String name, String address, double lat, double lng) {
}
