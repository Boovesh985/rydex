package com.rydex.tracking.payment;

import com.rydex.common.ws.WsDestinations;
import com.rydex.common.ws.WsPush;
import com.rydex.events.EventEnvelope;
import com.rydex.events.PaymentPayload;
import com.rydex.events.PaymentPayload.Kind;
import com.rydex.messaging.NonRetryableEventException;
import com.rydex.messaging.ProcessedEvents;
import com.rydex.tracking.support.AfterCommit;
import com.rydex.tracking.ws.PushRelay;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tells riders (and, when money reaches them, drivers) what happened to a payment, exactly once per
 * {@code payment.events} event, on {@code /user/queue/notifications}.
 */
@Component
public class PaymentNotifications {

    /**
     * A payment notification. Shares the channel with ride notifications; {@code type} tells them apart.
     *
     * @param refundAmount PAYMENT_REFUNDED: what this refund returned
     */
    public record PaymentNotification(UUID eventId, String type, UUID rideId, UUID paymentId, Kind kind,
                                      PaymentPayload.Status status, BigDecimal amount, BigDecimal refundAmount,
                                      String currency, String title, String message, Instant at) {
    }

    private final ProcessedEvents processed;
    private final PushRelay relay;
    private final String consumer;

    PaymentNotifications(ProcessedEvents processed, PushRelay relay,
                         @Value("${spring.kafka.consumer.group-id}") String consumer) {
        this.processed = processed;
        this.relay = relay;
        this.consumer = consumer;
    }

    /** Returns how many notifications were pushed (0 for a duplicate). */
    @Transactional
    public int apply(EventEnvelope<PaymentPayload> event) {
        if (!processed.firstDelivery(consumer, event.eventId())) {
            return 0;
        }
        PaymentPayload p = event.payload();
        if (p == null || p.riderId() == null || p.amount() == null) {
            throw new NonRetryableEventException(event.eventType() + " without payment details: " + event.eventId());
        }
        List<WsPush> pushes = forEvent(event, p);
        AfterCommit.run(() -> pushes.forEach(relay::publish));
        return pushes.size();
    }

    static List<WsPush> forEvent(EventEnvelope<PaymentPayload> event, PaymentPayload p) {
        boolean fare = p.kind() == Kind.RIDE_FARE;
        String amount = rupees(p.amount());
        List<WsPush> pushes = new ArrayList<>(2);
        switch (event.eventType()) {
            case PAYMENT_INITIATED -> pushes.add(push(p.riderId(), event, p, "Payment due",
                    "Pay " + amount + (fare ? " for your trip." : " for the late cancellation.")));
            case PAYMENT_SUCCEEDED -> {
                pushes.add(push(p.riderId(), event, p, "Payment received",
                        amount + (fare ? " paid for your trip. Thanks for riding with Rydex." : " cancellation fee paid.")));
                if (p.driverId() != null) {
                    pushes.add(push(p.driverId(), event, p, fare ? "Fare received" : "Cancellation fee received",
                            amount + (fare ? " for your last trip." : " for the cancelled ride.")));
                }
            }
            case PAYMENT_FAILED -> pushes.add(push(p.riderId(), event, p, "Payment failed",
                    (p.reason() != null ? p.reason() + ". " : "") + "Please pay " + amount + " again."));
            case PAYMENT_REFUNDED -> pushes.add(push(p.riderId(), event, p, "Refund issued",
                    rupees(p.refundAmount() != null ? p.refundAmount() : p.amount()) + " refunded"
                            + (p.reason() != null ? ": " + p.reason() : "") + ". It reaches your account in a few days."));
            default -> {
                // ride events are handled by RideProjection
            }
        }
        return pushes;
    }

    private static WsPush push(UUID user, EventEnvelope<PaymentPayload> event, PaymentPayload p, String title,
                               String message) {
        return WsPush.toUser(user, WsDestinations.NOTIFICATIONS, new PaymentNotification(event.eventId(),
                event.eventType().name(), UUID.fromString(event.rideId()), p.paymentId(), p.kind(), p.status(),
                p.amount(), p.refundAmount(), p.currency(), title, message, event.occurredAt()));
    }

    private static String rupees(BigDecimal value) {
        return "Rs " + value.stripTrailingZeros().toPlainString();
    }
}
