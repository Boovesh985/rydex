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
 * @param simulated       booked by an automated (NPC) rider of the living city (ADR 0016): paid
 *                        through the mock provider; null (older events) means false
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
        BigDecimal cancellationFee,
        Boolean simulated) {

    /** Every event but a charged cancellation, for a real rider. */
    public RidePayload(UUID riderId, UUID driverId, RideType rideType, GeoPoint pickup, GeoPoint drop, BigDecimal fare,
                       Integer estDistanceM, Integer estDurationS, String reason) {
        this(riderId, driverId, rideType, pickup, drop, fare, estDistanceM, estDurationS, reason, null, null);
    }

    /** For a real rider. */
    public RidePayload(UUID riderId, UUID driverId, RideType rideType, GeoPoint pickup, GeoPoint drop, BigDecimal fare,
                       Integer estDistanceM, Integer estDurationS, String reason, BigDecimal cancellationFee) {
        this(riderId, driverId, rideType, pickup, drop, fare, estDistanceM, estDurationS, reason, cancellationFee, null);
    }

    /** Whether the rider is an NPC of the living city. */
    public boolean bySimulatedRider() {
        return Boolean.TRUE.equals(simulated);
    }
}
