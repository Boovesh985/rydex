package com.rydex.driver.events;

import com.rydex.driver.matching.MatchingService;
import com.rydex.events.EventEnvelope;
import com.rydex.events.RidePayload;
import com.rydex.messaging.NonRetryableEventException;
import com.rydex.messaging.ProcessedEvents;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feeds ride-service's lifecycle events into matching, exactly once per event:
 * RIDE_REQUESTED starts matching, RIDE_CANCELLED stops it (or frees the assigned driver),
 * RIDE_COMPLETED frees the driver.
 */
@Component
public class RideLifecycleHandler {

    public enum Result {
        APPLIED,
        DUPLICATE
    }

    private final ProcessedEvents processed;
    private final MatchingService matching;
    private final String consumer;

    RideLifecycleHandler(ProcessedEvents processed, MatchingService matching,
                         @Value("${spring.kafka.consumer.group-id}") String consumer) {
        this.processed = processed;
        this.matching = matching;
        this.consumer = consumer;
    }

    @Transactional
    public Result apply(EventEnvelope<RidePayload> event) {
        if (!processed.firstDelivery(consumer, event.eventId())) {
            return Result.DUPLICATE;
        }
        UUID rideId = parseId(event.rideId());
        switch (event.eventType()) {
            case RIDE_REQUESTED -> matching.onRideRequested(rideId, requireBookable(event));
            case RIDE_CANCELLED -> matching.onRideCancelled(rideId);
            case RIDE_COMPLETED -> matching.onRideCompleted(rideId);
            default -> throw new IllegalArgumentException("not a ride lifecycle event: " + event.eventType());
        }
        return Result.APPLIED;
    }

    private static RidePayload requireBookable(EventEnvelope<RidePayload> event) {
        RidePayload p = event.payload();
        if (p == null || p.riderId() == null || p.rideType() == null || p.pickup() == null || p.drop() == null
                || p.fare() == null) {
            throw new NonRetryableEventException("RIDE_REQUESTED is missing ride details: " + event.eventId());
        }
        return p;
    }

    private static UUID parseId(String rideId) {
        try {
            return UUID.fromString(rideId);
        } catch (IllegalArgumentException e) {
            throw new NonRetryableEventException("ride id is not a UUID: " + rideId);
        }
    }
}
