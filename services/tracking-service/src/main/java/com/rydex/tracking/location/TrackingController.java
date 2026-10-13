package com.rydex.tracking.location;

import com.rydex.tracking.location.LocationHistory.PathPoint;
import com.rydex.tracking.ride.RideAccess;
import com.rydex.tracking.ride.TrackedRide;
import com.rydex.tracking.ride.TrackedRideRepository;
import com.rydex.tracking.security.AuthUser;
import com.rydex.tracking.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST reads for screens that open mid-ride or replay a trip: the WebSocket only carries what
 * happens next. Same access rule as the location topic: the ride's rider, its driver or an admin;
 * anyone else gets 404.
 */
@RestController
@RequestMapping("/api/tracking/rides/{rideId}")
@Tag(name = "Tracking")
class TrackingController {

    private final TrackedRideRepository rides;
    private final RideAccess access;
    private final LocationService locations;
    private final LocationHistory history;

    TrackingController(TrackedRideRepository rides, RideAccess access, LocationService locations, LocationHistory history) {
        this.rides = rides;
        this.access = access;
        this.locations = locations;
        this.history = history;
    }

    @GetMapping("/location")
    @Operation(summary = "Where the ride's driver is now, with the ETA (404 until a driver is assigned and has reported)")
    LiveLocation location(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID rideId) {
        TrackedRide ride = watchable(jwt, rideId);
        if (!ride.isActive()) {
            throw ApiException.notFound("Live location (the ride is " + ride.getStatus() + ")");
        }
        return locations.current(ride).orElseThrow(() -> ApiException.notFound("Driver location"));
    }

    @GetMapping("/path")
    @Operation(summary = "The driver's path during the ride, one point every ~5 s, oldest first")
    List<PathPoint> path(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID rideId) {
        watchable(jwt, rideId);
        return history.path(rideId);
    }

    private TrackedRide watchable(Jwt jwt, UUID rideId) {
        AuthUser user = AuthUser.from(jwt);
        TrackedRide ride = rides.findById(rideId).orElseThrow(() -> ApiException.notFound("Ride"));
        if (!access.canWatch(user, rideId)) {
            throw ApiException.notFound("Ride");
        }
        return ride;
    }
}
