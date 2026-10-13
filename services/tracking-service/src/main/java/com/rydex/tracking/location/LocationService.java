package com.rydex.tracking.location;

import com.rydex.common.DriverStatus;
import com.rydex.common.geo.GeoPoint;
import com.rydex.common.geo.Geohash;
import com.rydex.common.ws.WsDestinations;
import com.rydex.common.ws.WsPush;
import com.rydex.events.DriverLocationEvent;
import com.rydex.presence.DriverLocation;
import com.rydex.presence.PresenceStore;
import com.rydex.presence.PresenceStore.HeartbeatResult;
import com.rydex.tracking.ride.TrackedRide;
import com.rydex.tracking.ride.TrackedRideRepository;
import com.rydex.tracking.security.AuthUser;
import com.rydex.tracking.ws.ClientErrors;
import com.rydex.tracking.ws.PushRelay;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Driver location ingest (master prompt §10). For every update a driver sends over the WebSocket:
 * <ol>
 *   <li>drop it if it is invalid or the driver sent one less than 500 ms ago (max 2/s);</li>
 *   <li>record it in Redis exactly as driver-service would ({@link PresenceStore}): last position,
 *       heartbeat and, if the driver is available, the GEO search index;</li>
 *   <li>if the driver is on a ride, broadcast it with a live ETA to {@code /topic/rides/{id}/location};</li>
 *   <li>publish it on {@code driver.location} for the AI features and the trip history.</li>
 * </ol>
 */
@Service
public class LocationService {

    public enum Outcome {
        ACCEPTED,
        INVALID,
        THROTTLED
    }

    private final PresenceStore presence;
    private final TrackedRideRepository rides;
    private final EtaService etas;
    private final PushRelay relay;
    private final DriverLocationPublisher publisher;
    private final ClientErrors errors;
    private final Clock clock;
    private final Counter accepted;
    private final Counter invalid;
    private final Counter throttled;

    LocationService(PresenceStore presence, TrackedRideRepository rides, EtaService etas, PushRelay relay,
                    DriverLocationPublisher publisher, ClientErrors errors, Clock clock, MeterRegistry meters) {
        this.presence = presence;
        this.rides = rides;
        this.etas = etas;
        this.relay = relay;
        this.publisher = publisher;
        this.errors = errors;
        this.clock = clock;
        this.accepted = counter(meters, "accepted");
        this.invalid = counter(meters, "invalid");
        this.throttled = counter(meters, "throttled");
    }

    public Outcome ingest(AuthUser driver, LocationUpdate update) {
        String problem = update.problem();
        if (problem != null) {
            invalid.increment();
            errors.send(driver, "INVALID_LOCATION", problem);
            return Outcome.INVALID;
        }
        if (!presence.tryClaimLocationSlot(driver.id())) {
            throttled.increment();
            errors.send(driver, "RATE_LIMITED", "At most 2 location updates per second; send one every 3 s");
            return Outcome.THROTTLED;
        }
        Instant now = clock.instant();
        HeartbeatResult heartbeat = presence.heartbeat(driver.id(), update.lat(), update.lng(), update.heading(),
                update.speed(), now);
        Optional<TrackedRide> ride = rides.activeRideOf(driver.id());
        ride.ifPresent(r -> relay.publish(WsPush.toTopic(WsDestinations.rideLocation(r.getId()),
                live(r, driver.id(), update.lat(), update.lng(), update.heading(), update.speed(), now))));
        publisher.publish(new DriverLocationEvent(UUID.randomUUID(), driver.id(), ride.map(TrackedRide::getId).orElse(null),
                update.lat(), update.lng(), update.heading(), update.speed(),
                Geohash.encode(update.lat(), update.lng(), Geohash.MATCHING_PRECISION),
                status(driver.id(), heartbeat, ride.isPresent()), now));
        accepted.increment();
        return Outcome.ACCEPTED;
    }

    /** The driver's last known position on {@code ride}, with ETA; empty before any update. */
    public Optional<LiveLocation> current(TrackedRide ride) {
        if (ride.getDriverId() == null) {
            return Optional.empty();
        }
        Optional<DriverLocation> loc = presence.location(ride.getDriverId());
        return loc.map(l -> live(ride, ride.getDriverId(), l.lat(), l.lng(), l.heading(), l.speed(), l.at()));
    }

    private LiveLocation live(TrackedRide ride, UUID driverId, double lat, double lng, Double heading, Double speed,
                              Instant at) {
        LiveLocation.Phase phase = switch (ride.getStatus()) {
            case DRIVER_ARRIVED -> LiveLocation.Phase.AT_PICKUP;
            case IN_PROGRESS, COMPLETED -> LiveLocation.Phase.TO_DROP;
            default -> LiveLocation.Phase.TO_PICKUP;
        };
        GeoPoint position = GeoPoint.of(lat, lng);
        GeoPoint target = phase == LiveLocation.Phase.TO_DROP ? ride.drop() : ride.pickup();
        EtaService.Eta eta = etas.eta(ride.getId(), phase, position, target);
        return new LiveLocation(ride.getId(), driverId, lat, lng, heading, speed, phase, eta.seconds(), eta.meters(),
                eta.estimated(), at);
    }

    private DriverStatus status(UUID driverId, HeartbeatResult heartbeat, boolean onRide) {
        if (onRide) {
            return DriverStatus.ON_TRIP;
        }
        return switch (heartbeat) {
            case INDEXED -> DriverStatus.AVAILABLE;
            case NOT_AVAILABLE -> presence.status(driverId).orElse(null);
            case STATE_MISSING -> null;
        };
    }

    private static Counter counter(MeterRegistry meters, String result) {
        return Counter.builder("location_updates_total")
                .description("Driver location updates received over the WebSocket")
                .tag("result", result)
                .register(meters);
    }
}
