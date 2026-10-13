package com.rydex.tracking.ride;

import com.rydex.tracking.security.AuthUser;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Who may watch a ride's live location: its rider, its driver, or an admin.
 *
 * <p>The projection answers almost every time. A rider who subscribes right after booking can be
 * faster than the RIDE_REQUESTED event, so when the projection does not know the ride yet, the
 * question goes to ride-service ({@code GET /api/rides/{id}} with the user's own token, which
 * answers 404 to anyone else).
 */
@Component
public class RideAccess {

    private static final Logger log = LoggerFactory.getLogger(RideAccess.class);

    private final TrackedRideRepository rides;
    private final RestClient rideService;

    RideAccess(TrackedRideRepository rides, @Qualifier("rideServiceRestClient") RestClient rideService) {
        this.rides = rides;
        this.rideService = rideService;
    }

    public boolean canWatch(AuthUser user, UUID rideId) {
        if (user.isAdmin()) {
            return true;
        }
        return rides.findById(rideId)
                .map(r -> r.isParticipant(user.id()))
                .orElseGet(() -> rideServiceAllows(user, rideId));
    }

    private boolean rideServiceAllows(AuthUser user, UUID rideId) {
        try {
            return rideService.get()
                    .uri("/api/rides/{id}", rideId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token())
                    .exchange((request, response) -> response.getStatusCode().is2xxSuccessful());
        } catch (RuntimeException e) {
            log.warn("Could not ask ride-service about ride {}: {}", rideId, e.toString());
            return false;
        }
    }
}
