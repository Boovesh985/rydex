package com.rydex.events;

import com.rydex.common.RideType;
import com.rydex.common.geo.GeoPoint;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Payload of every {@code ride.events} message. Fields that do not apply to an
 * event type are null (e.g. {@code driverId} on RIDE_REQUESTED).
 */
public record RidePayload(
        UUID riderId,
        UUID driverId,
        RideType rideType,
        GeoPoint pickup,
        GeoPoint drop,
        BigDecimal fare,
        Integer estDistanceM,
        Integer estDurationS,
        String reason) {
}
