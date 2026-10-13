package com.rydex.ride.events;

import com.rydex.events.EventEnvelope;
import com.rydex.events.PaymentPayload;
import com.rydex.messaging.NonRetryableEventException;
import com.rydex.messaging.ProcessedEvents;
import com.rydex.ride.ride.Ride;
import com.rydex.ride.ride.RideRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mirrors payment-service's outcomes onto the ride (payment status, refunded amount), exactly once
 * per event, so ride history shows whether each ride is paid. A ride's payment events share its
 * partition, so they arrive in the order they happened and the latest one wins.
 */
@Component
public class PaymentOutcomeHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutcomeHandler.class);

    public enum Result {
        APPLIED,
        DUPLICATE,
        IGNORED
    }

    private final ProcessedEvents processed;
    private final RideRepository rides;
    private final String consumer;

    PaymentOutcomeHandler(ProcessedEvents processed, RideRepository rides,
                          @Value("${spring.kafka.consumer.group-id}") String consumer) {
        this.processed = processed;
        this.rides = rides;
        this.consumer = consumer;
    }

    @Transactional
    public Result apply(EventEnvelope<PaymentPayload> event) {
        if (!processed.firstDelivery(consumer, event.eventId())) {
            return Result.DUPLICATE;
        }
        PaymentPayload p = event.payload();
        if (p == null || p.status() == null) {
            throw new NonRetryableEventException(event.eventType() + " without a payment status: " + event.eventId());
        }
        Ride ride = rides.findById(parseId(event.rideId())).orElse(null);
        if (ride == null) {
            log.warn("Ignoring {} for unknown ride {}", event.eventType(), event.rideId());
            return Result.IGNORED;
        }
        ride.paymentChanged(p.status(), p.refundedAmount());
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
