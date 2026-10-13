package com.rydex.tracking.location;

import java.time.Instant;
import java.util.UUID;

/**
 * What a ride's watchers receive on {@code /topic/rides/{rideId}/location}, and what
 * {@code GET /api/tracking/rides/{rideId}/location} returns.
 *
 * @param phase         {@code TO_PICKUP}, {@code AT_PICKUP} or {@code TO_DROP}
 * @param etaS          seconds to the pickup (TO_PICKUP) or the drop (TO_DROP); 0 at the pickup
 * @param distanceM     metres left to drive to the same point
 * @param etaEstimated  true when OSRM was unavailable and the ETA is a straight-line estimate
 */
public record LiveLocation(UUID rideId, UUID driverId, double lat, double lng, Double heading, Double speed,
                           Phase phase, int etaS, int distanceM, boolean etaEstimated, Instant at) {

    public enum Phase {
        TO_PICKUP,
        AT_PICKUP,
        TO_DROP
    }
}
