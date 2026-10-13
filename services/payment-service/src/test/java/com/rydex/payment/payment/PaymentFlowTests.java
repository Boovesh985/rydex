package com.rydex.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.rydex.events.EventEnvelope;
import com.rydex.events.EventType;
import com.rydex.events.PaymentPayload;
import com.rydex.events.PaymentPayload.Kind;
import com.rydex.events.PaymentPayload.Provider;
import com.rydex.events.PaymentPayload.Status;
import com.rydex.events.RidePayload;
import com.rydex.events.Topics;
import com.rydex.payment.IntegrationTest;
import com.rydex.payment.KafkaProbe;
import com.rydex.payment.TestTokens;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Ride events in, charges and payment.events out, with the mock provider. */
class PaymentFlowTests extends IntegrationTest {

    @Autowired
    MeterRegistry meters;

    @Test
    void completedRideIsChargedAndAnnounced() {
        TestRide ride = newRide();

        complete(ride, "339.00");
        Payment paid = awaitStatus(ride.id(), Status.SUCCEEDED);

        assertThat(paid.getKind()).isEqualTo(Kind.RIDE_FARE);
        assertThat(paid.getAmount()).isEqualByComparingTo("339.00");
        assertThat(paid.getProvider()).isEqualTo(Provider.MOCK);
        assertThat(paid.getProviderRef()).startsWith("mock_ch_");
        assertThat(paid.getRiderId()).isEqualTo(ride.riderId());
        assertThat(paid.getDriverId()).isEqualTo(ride.driverId());
        assertThat(paid.getPaidAt()).isNotNull();
        assertThat(paid.getNextAttemptAt()).isNull();

        // the mock charges at once: no PAYMENT_INITIATED, just the outcome
        var events = paymentEvents(ride.id(), 1);
        assertThat(KafkaProbe.header(events.getFirst(), "eventType")).isEqualTo("PAYMENT_SUCCEEDED");
        EventEnvelope<PaymentPayload> event = parse(events.getFirst());
        assertThat(event.rideId()).isEqualTo(ride.id().toString());
        assertThat(event.payload().paymentId()).isEqualTo(paid.getId());
        assertThat(event.payload().amount()).isEqualByComparingTo("339");
        assertThat(event.payload().status()).isEqualTo(Status.SUCCEEDED);
        assertThat(event.payload().riderId()).isEqualTo(ride.riderId());
    }

    @Test
    void aRideIsChargedOnceHoweverOftenItsEventsArrive() {
        TestRide ride = newRide();
        var completed = publish(EventType.RIDE_COMPLETED, ride, ride.completed(new BigDecimal("339.00")));
        // redelivery of the same event, and a stray second event for the same ride
        kafka().publish(Topics.RIDE_EVENTS, completed.rideId(), json.writeValueAsString(completed), "RIDE_COMPLETED");
        var stray = publish(EventType.RIDE_COMPLETED, ride, ride.completed(new BigDecimal("400.00")));

        awaitStatus(ride.id(), Status.SUCCEEDED);
        await().atMost(WAIT).until(() -> processed(stray.eventId()));

        assertThat(jdbc.sql("SELECT count(*) FROM payments WHERE ride_id = :id").param("id", ride.id())
                .query(Long.class).single()).isEqualTo(1);
        assertThat(payments.findByRideId(ride.id()).orElseThrow().getAmount()).isEqualByComparingTo("339");
        assertThat(kafka().collect(Topics.PAYMENT_EVENTS, r -> ride.id().toString().equals(r.key()),
                Duration.ofSeconds(2))).hasSize(1);
    }

    @Test
    void lateCancellationIsChargedTheFee() {
        TestRide ride = newRide();

        publish(EventType.RIDE_CANCELLED, ride, ride.cancelled(new BigDecimal("50.00")));
        Payment fee = awaitStatus(ride.id(), Status.SUCCEEDED);

        assertThat(fee.getKind()).isEqualTo(Kind.CANCELLATION_FEE);
        assertThat(fee.getAmount()).isEqualByComparingTo("50");
        assertThat(parse(paymentEvents(ride.id(), 1).getFirst()).payload().kind()).isEqualTo(Kind.CANCELLATION_FEE);
    }

    @Test
    void freeCancellationChargesNothing() {
        TestRide ride = newRide();

        var cancelled = publish(EventType.RIDE_CANCELLED, ride, ride.cancelled(null));
        await().atMost(WAIT).until(() -> processed(cancelled.eventId()));

        assertThat(payments.findByRideId(ride.id())).isEmpty();
    }

    @Test
    void declinedChargeFailsAndTheRiderCanRetry() {
        TestRide ride = newRide();
        double failuresBefore = failures();

        complete(ride, "1500.00"); // above the test's mock limit of 1000
        Payment failed = awaitStatus(ride.id(), Status.FAILED);

        assertThat(failed.getFailureReason()).contains("declined");
        assertThat(failed.getNextAttemptAt()).as("the mock waits for the rider to retry").isNull();
        var event = paymentEvents(ride.id(), 1).getFirst();
        assertThat(KafkaProbe.header(event, "eventType")).isEqualTo("PAYMENT_FAILED");
        assertThat(parse(event).payload().reason()).contains("declined");
        assertThat(failures()).isEqualTo(failuresBefore + 1);

        String rider = TestTokens.rider(ride.riderId());
        assertThat(api().post("/api/payments/" + failed.getId() + "/retry", TestTokens.rider(ride.driverId()), null)
                .getStatusCode().value()).isEqualTo(404);
        var retried = api().post("/api/payments/" + failed.getId() + "/retry", rider, null);
        assertThat(retried.getStatusCode().value()).isEqualTo(200);
        assertThat(retried.getBody().path("status").asString()).isIn("PENDING", "FAILED");

        // charged again (and declined again: the amount is still above the limit)
        await().atMost(WAIT).until(() -> payments.findById(failed.getId()).orElseThrow().getAttempts() == 2);
        assertThat(awaitStatus(ride.id(), Status.FAILED).getAttempts()).isEqualTo(2);
        assertThat(paymentEvents(ride.id(), 2)).extracting(r -> KafkaProbe.header(r, "eventType"))
                .containsExactly("PAYMENT_FAILED", "PAYMENT_FAILED");
    }

    @Test
    void onlyAFailedPaymentCanBeRetried() {
        TestRide ride = newRide();
        Payment p = complete(ride, "339.00");
        awaitStatus(ride.id(), Status.SUCCEEDED);

        var res = api().post("/api/payments/" + p.getId() + "/retry", TestTokens.rider(ride.riderId()), null);

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().path("detail").asString()).contains("SUCCEEDED");
    }

    @Test
    void completedRideWithoutAFareGoesToTheDeadLetterTopic() {
        TestRide ride = newRide();
        RidePayload broken = new RidePayload(ride.riderId(), ride.driverId(), null, CENTER, AIRPORT, null, null, null,
                null);

        var event = publish(EventType.RIDE_COMPLETED, ride, broken);

        kafka().await(Topics.deadLetter(Topics.RIDE_EVENTS), r -> r.value().contains(event.eventId().toString()), 1, WAIT);
        assertThat(payments.findByRideId(ride.id())).isEmpty();
    }

    private double failures() {
        var counter = meters.find("payment_failures_total").tag("provider", "MOCK").tag("kind", "RIDE_FARE").counter();
        return counter == null ? 0 : counter.count();
    }
}
