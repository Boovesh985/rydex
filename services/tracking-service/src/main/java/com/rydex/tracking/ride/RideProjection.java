package com.rydex.tracking.ride;

import com.rydex.common.ws.WsPush;
import com.rydex.events.EventEnvelope;
import com.rydex.events.EventType;
import com.rydex.events.RidePayload;
import com.rydex.messaging.NonRetryableEventException;
import com.rydex.messaging.ProcessedEvents;
import com.rydex.tracking.support.AfterCommit;
import com.rydex.tracking.ws.PushRelay;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps {@link TrackedRide} in step with {@code ride.events}, exactly once per event, and pushes
 * the matching notifications once the change is committed.
 */
@Component
public class RideProjection {

    private static final Logger log = LoggerFactory.getLogger(RideProjection.class);

    public enum Result {
        APPLIED,
        DUPLICATE,
        IGNORED
    }

    private final ProcessedEvents processed;
    private final TrackedRideRepository rides;
    private final PushRelay relay;
    private final Clock clock;
    private final String consumer;

    RideProjection(ProcessedEvents processed, TrackedRideRepository rides, PushRelay relay, Clock clock,
                   @Value("${spring.kafka.consumer.group-id}") String consumer) {
        this.processed = processed;
        this.rides = rides;
        this.relay = relay;
        this.clock = clock;
        this.consumer = consumer;
    }

    @Transactional
    public Result apply(EventEnvelope<RidePayload> event) {
        if (!processed.firstDelivery(consumer, event.eventId())) {
            return Result.DUPLICATE;
        }
        UUID rideId = parseId(event.rideId());
        RidePayload p = event.payload();
        if (event.eventType() == EventType.DRIVER_ASSIGNED && (p == null || p.driverId() == null)) {
            throw new NonRetryableEventException("DRIVER_ASSIGNED without a driverId: " + event.eventId());
        }
        TrackedRide ride = rides.findById(rideId).orElse(null);
        boolean created = false;
        if (ride == null) {
            // every ride event carries the ride's details, so whichever arrives first can create it
            if (p == null || p.riderId() == null || p.pickup() == null || p.drop() == null) {
                throw new NonRetryableEventException(event.eventType() + " for an unknown ride without ride details: "
                        + event.eventId());
            }
            ride = rides.save(TrackedRide.requested(rideId, p, clock.instant()));
            created = true;
        }
        boolean changed = ride.apply(event.eventType(), p, clock.instant());
        if (!changed && !created) {
            log.debug("Ignoring {} for ride {}: it is {}", event.eventType(), rideId, ride.getStatus());
            return Result.IGNORED;
        }
        List<WsPush> pushes = Notifications.forEvent(event, ride);
        AfterCommit.run(() -> pushes.forEach(relay::publish));
        return Result.APPLIED;
    }

    private static UUID parseId(String rideId) {
        try {
            return UUID.fromString(rideId);
        } catch (IllegalArgumentException e) {
            throw new NonRetryableEventException("ride id is not a UUID: " + rideId);
        }
    }
}
