package com.rydex.events;

import com.rydex.common.RideType;
import com.rydex.common.geo.GeoPoint;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Payload of every {@code ride.events} message. Fields that do not apply to an
 * event type are null (e.g. {@code driverId} on RIDE_REQUESTED).
 *
 * @param fare            INR: the quoted fare, or the final fare once completed
 * @param cancellationFee RIDE_CANCELLED only: what the rider is charged for cancelling late
 *                        (after the driver arrived); null when the cancellation is free
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
        String reason,
        BigDecimal cancellationFee) {

    /** Every event but a charged cancellation. */
    public RidePayload(UUID riderId, UUID driverId, RideType rideType, GeoPoint pickup, GeoPoint drop, BigDecimal fare,
                       Integer estDistanceM, Integer estDurationS, String reason) {
        this(riderId, driverId, rideType, pickup, drop, fare, estDistanceM, estDurationS, reason, null);
    }
}
