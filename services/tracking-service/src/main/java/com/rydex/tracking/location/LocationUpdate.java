package com.rydex.tracking.location;

/**
 * What a driver sends to {@code /app/driver.location} every ~3 s.
 *
 * @param heading degrees clockwise from north, optional
 * @param speed   metres per second, optional
 * @param ts      the device's clock (epoch ms), informational; the server's time is what counts
 */
public record LocationUpdate(Double lat, Double lng, Double heading, Double speed, Long ts) {

    /** Why the update cannot be used, or null if it is fine. */
    String problem() {
        if (lat == null || lng == null) {
            return "lat and lng are required";
        }
        if (lat.isNaN() || lng.isNaN() || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            return "lat must be within -90..90 and lng within -180..180";
        }
        if (heading != null && (heading.isNaN() || heading < 0 || heading > 360)) {
            return "heading must be within 0..360";
        }
        if (speed != null && (speed.isNaN() || speed < 0 || speed > 100)) {
            return "speed must be within 0..100 m/s";
        }
        return null;
    }
}
