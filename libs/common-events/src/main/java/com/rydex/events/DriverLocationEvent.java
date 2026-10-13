package com.rydex.events;

import com.rydex.common.DriverStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One driver position, published by tracking-service on {@link Topics#DRIVER_LOCATION}, keyed
 * by driver id. Telemetry, not a domain event: it is sent straight to Kafka (no outbox) and a
 * lost update is replaced by the next one 3 s later. JSON Schema:
 * contracts/events/driver-location.schema.json.
 *
 * @param rideId the ride the driver is on, or null when they are not on one
 * @param status the driver's availability as known to the presence projection, null if unknown
 */
public record DriverLocationEvent(
        UUID eventId,
        UUID driverId,
        UUID rideId,
        double lat,
        double lng,
        Double heading,
        Double speed,
        String geohash,
        DriverStatus status,
        Instant recordedAt) {

    public DriverLocationEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(driverId, "driverId");
        Objects.requireNonNull(recordedAt, "recordedAt");
    }

    /** Kafka message key: one driver's positions stay in order on one partition. */
    public String key() {
        return driverId.toString();
    }
}
