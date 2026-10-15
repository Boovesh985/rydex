package com.rydex.payment.events;

import com.rydex.events.EventEnvelope;
import com.rydex.events.PaymentPayload.Kind;
import com.rydex.events.RidePayload;
import com.rydex.messaging.NonRetryableEventException;
import com.rydex.messaging.ProcessedEvents;
import com.rydex.payment.payment.PaymentService;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns ride events into charges, exactly once per event: RIDE_COMPLETED charges the fare,
 * RIDE_CANCELLED charges the cancellation fee when ride-service set one (the rider cancelled
 * after the driver arrived). A ride is never charged twice.
 */
@Component
public class RideChargeHandler {

    private static final Logger log = LoggerFactory.getLogger(RideChargeHandler.class);

    public enum Result {
        CHARGED,
        DUPLICATE,
        IGNORED
    }

    private final ProcessedEvents processed;
    private final PaymentService payments;
    private final String consumer;

    RideChargeHandler(ProcessedEvents processed, PaymentService payments,
                      @Value("${spring.kafka.consumer.group-id}") String consumer) {
        this.processed = processed;
        this.payments = payments;
        this.consumer = consumer;
    }

    @Transactional
    public Result apply(EventEnvelope<RidePayload> event) {
        if (!processed.firstDelivery(consumer, event.eventId())) {
            return Result.DUPLICATE;
        }
        UUID rideId = parseId(event.rideId());
        RidePayload p = event.payload();
        if (p == null || p.riderId() == null) {
            throw new NonRetryableEventException(event.eventType() + " without a riderId: " + event.eventId());
        }
        Kind kind;
        BigDecimal amount;
        switch (event.eventType()) {
            case RIDE_COMPLETED -> {
                kind = Kind.RIDE_FARE;
                amount = p.fare();
                if (amount == null || amount.signum() <= 0) {
                    throw new NonRetryableEventException("RIDE_COMPLETED without a fare: " + event.eventId());
                }
            }
            case RIDE_CANCELLED -> {
                kind = Kind.CANCELLATION_FEE;
                amount = p.cancellationFee();
                if (amount == null || amount.signum() <= 0) {
                    return Result.IGNORED; // a free cancellation
                }
            }
            default -> throw new IllegalArgumentException("not a chargeable ride event: " + event.eventType());
        }
        if (!payments.chargeForRide(rideId, p.riderId(), p.driverId(), kind, amount, p.bySimulatedRider())) {
            log.warn("Ride {} already has a payment; ignoring {}", rideId, event.eventType());
            return Result.IGNORED;
        }
        log.info("Charging Rs {} ({}) for ride {}", amount.toPlainString(), kind, rideId);
        return Result.CHARGED;
    }

    private static UUID parseId(String rideId) {
        try {
            return UUID.fromString(rideId);
        } catch (IllegalArgumentException e) {
            throw new NonRetryableEventException("ride id is not a UUID: " + rideId);
        }
    }
}
