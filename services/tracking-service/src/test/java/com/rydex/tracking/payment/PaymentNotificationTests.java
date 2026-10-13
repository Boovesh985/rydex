package com.rydex.tracking.payment;

import static com.rydex.tracking.StompTestClient.next;
import static com.rydex.tracking.StompTestClient.quiet;
import static org.assertj.core.api.Assertions.assertThat;

import com.rydex.events.EventEnvelope;
import com.rydex.events.EventType;
import com.rydex.events.PaymentPayload;
import com.rydex.events.PaymentPayload.Kind;
import com.rydex.events.PaymentPayload.Provider;
import com.rydex.events.PaymentPayload.Status;
import com.rydex.events.RidePayload;
import com.rydex.events.Topics;
import com.rydex.tracking.IntegrationTest;
import com.rydex.tracking.StompTestClient;
import com.rydex.tracking.TestTokens;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** payment.events reach the rider (and the driver when money reaches them) as notifications. */
class PaymentNotificationTests extends IntegrationTest {

    private EventEnvelope<PaymentPayload> publishPayment(EventType type, TestRide ride, Kind kind, Status status,
                                                         String refundAmount, String reason) {
        var payload = new PaymentPayload(UUID.randomUUID(), ride.riderId(), ride.driverId(), kind,
                new BigDecimal("339.00"), refundAmount == null ? BigDecimal.ZERO : new BigDecimal(refundAmount),
                refundAmount == null ? null : new BigDecimal(refundAmount), "INR", Provider.RAZORPAY, "order_1", status,
                reason);
        var event = EventEnvelope.of(type, ride.id().toString(), payload);
        kafka().publish(Topics.PAYMENT_EVENTS, event.rideId(), json.writeValueAsString(event), type.name());
        return event;
    }

    @Test
    void theRiderHearsEveryPaymentStepAndTheDriverWhenPaid() {
        TestRide ride = newRide();
        try (StompTestClient rider = connect(TestTokens.rider(ride.riderId()));
             StompTestClient driver = connect(TestTokens.driver(ride.driverId()))) {
            var riderInbox = rider.subscribe("/user/queue/notifications");
            var driverInbox = driver.subscribe("/user/queue/notifications");

            publishPayment(EventType.PAYMENT_INITIATED, ride, Kind.RIDE_FARE, Status.PENDING, null, null);
            JsonNode due = next(riderInbox);
            assertThat(due.path("type").asString()).isEqualTo("PAYMENT_INITIATED");
            assertThat(due.path("title").asString()).isEqualTo("Payment due");
            assertThat(due.path("message").asString()).isEqualTo("Pay Rs 339 for your trip.");
            assertThat(due.path("rideId").asString()).isEqualTo(ride.id().toString());

            publishPayment(EventType.PAYMENT_FAILED, ride, Kind.RIDE_FARE, Status.FAILED, null, "Payment declined by the bank");
            assertThat(next(riderInbox).path("message").asString())
                    .isEqualTo("Payment declined by the bank. Please pay Rs 339 again.");

            var paid = publishPayment(EventType.PAYMENT_SUCCEEDED, ride, Kind.RIDE_FARE, Status.SUCCEEDED, null, null);
            JsonNode received = next(riderInbox);
            assertThat(received.path("title").asString()).isEqualTo("Payment received");
            assertThat(received.path("status").asString()).isEqualTo("SUCCEEDED");
            assertThat(received.path("paymentId").asString()).isEqualTo(paid.payload().paymentId().toString());
            JsonNode earned = next(driverInbox);
            assertThat(earned.path("title").asString()).isEqualTo("Fare received");
            assertThat(earned.path("amount").decimalValue()).isEqualByComparingTo("339");

            // redelivery: nothing new
            kafka().publish(Topics.PAYMENT_EVENTS, paid.rideId(), json.writeValueAsString(paid), "PAYMENT_SUCCEEDED");
            publishPayment(EventType.PAYMENT_REFUNDED, ride, Kind.RIDE_FARE, Status.SUCCEEDED, "100.00", "Goodwill");
            JsonNode refund = next(riderInbox);
            assertThat(refund.path("title").asString()).isEqualTo("Refund issued");
            assertThat(refund.path("message").asString()).startsWith("Rs 100 refunded: Goodwill.");
            assertThat(refund.path("refundAmount").decimalValue()).isEqualByComparingTo("100");

            assertThat(quiet(riderInbox, Duration.ofSeconds(1))).isTrue();
            assertThat(quiet(driverInbox, Duration.ZERO)).as("drivers only hear about money they receive").isTrue();
        }
    }

    @Test
    void aLateCancellationMentionsTheFee() {
        TestRide ride = newRide();
        try (StompTestClient rider = connect(TestTokens.rider(ride.riderId()))) {
            var inbox = rider.subscribe("/user/queue/notifications");
            publish(EventType.RIDE_REQUESTED, ride);
            next(inbox);
            publish(EventType.DRIVER_ASSIGNED, ride);
            next(inbox);
            publish(EventType.DRIVER_ARRIVED, ride);
            next(inbox);

            var p = ride.payload("Changed plans");
            publish(EventEnvelope.of(EventType.RIDE_CANCELLED, ride.id().toString(),
                    new RidePayload(p.riderId(), p.driverId(), p.rideType(), p.pickup(), p.drop(), p.fare(),
                            p.estDistanceM(), p.estDurationS(), p.reason(), new BigDecimal("50.00"))));
            assertThat(next(inbox).path("message").asString())
                    .isEqualTo("Changed plans. A cancellation fee of Rs 50 applies: the driver had arrived.");

            publishPayment(EventType.PAYMENT_SUCCEEDED, ride, Kind.CANCELLATION_FEE, Status.SUCCEEDED, null, null);
            assertThat(next(inbox).path("message").asString()).isEqualTo("Rs 339 cancellation fee paid.");
        }
    }
}
