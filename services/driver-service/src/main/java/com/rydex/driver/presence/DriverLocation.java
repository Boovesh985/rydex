package com.rydex.driver.presence;

import java.time.Instant;
import java.util.UUID;

/** Last reported position of a driver, read from {@code driver:{id}:loc}. */
public record DriverLocation(UUID driverId, double lat, double lng, Double heading, Double speed,
                             String geohash, Instant at) {
}
