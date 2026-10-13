package com.rydex.ride.events;

import com.rydex.events.EventEnvelope;
import com.rydex.events.RidePayload;
import com.rydex.messaging.NonRetryableEventException;
import com.rydex.messaging.ProcessedEvents;
import com.rydex.ride.ride.Ride;
import com.rydex.ride.ride.RideRepository;
import com.rydex.ride.ride.RideService;
import com.rydex.ride.ride.RideStatus;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies driver-service's matching outcomes (DRIVER_ASSIGNED, NO_DRIVER_FOUND) to rides,
 * exactly once per event. An outcome for a ride that has moved on (typically: the rider
 * cancelled while the driver was accepting) is ignored; driver-service frees the driver when
 * it sees the RIDE_CANCELLED event.
 */
@Component
public class MatchingOutcomeHandler {

    private static final Logger log = LoggerFactory.getLogger(MatchingOutcomeHandler.class);
    static final String ACTOR = "SYSTEM:driver-service";

    public enum Result {
        APPLIED,
        DUPLICATE,
        IGNORED
    }

    private final ProcessedEvents processed;
    private final RideRepository rides;
    private final RideService rideService;
    private final String consumer;

    MatchingOutcomeHandler(ProcessedEvents processed, RideRepository rides, RideService rideService,
                           @Value("${spring.kafka.consumer.group-id}") String consumer) {
        this.processed = processed;
        this.rides = rides;
        this.rideService = rideService;
        this.consumer = consumer;
    }

    @Transactional
    public Result apply(EventEnvelope<RidePayload> event) {
        if (!processed.firstDelivery(consumer, event.eventId())) {
            return Result.DUPLICATE;
        }
        UUID rideId = parseId(event.rideId());
        Ride ride = rides.findById(rideId).orElse(null);
        if (ride == null) {
            log.warn("Ignoring {} for unknown ride {}", event.eventType(), rideId);
            return Result.IGNORED;
        }
        // checked up front rather than catching the state machine's exception, which would
        // mark this transaction rollback-only and lose the processed-event marker
        if (ride.getStatus() != RideStatus.REQUESTED) {
            log.info("Ignoring {} for ride {}: it is already {}", event.eventType(), rideId, ride.getStatus());
            return Result.IGNORED;
        }
        switch (event.eventType()) {
            case DRIVER_ASSIGNED -> {
                if (event.payload() == null || event.payload().driverId() == null) {
                    throw new NonRetryableEventException("DRIVER_ASSIGNED without a driverId: " + event.eventId());
                }
                rideService.assignDriver(rideId, event.payload().driverId(), ACTOR);
                log.info("Ride {} assigned to driver {}", rideId, event.payload().driverId());
            }
            case NO_DRIVER_FOUND -> {
                rideService.markNoDriverFound(rideId, ACTOR);
                log.info("No driver found for ride {}", rideId);
            }
            default -> throw new IllegalArgumentException("not a matching outcome: " + event.eventType());
        }
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
