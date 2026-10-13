package com.rydex.ride.events;

import static com.rydex.ride.TestApi.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.rydex.events.EventEnvelope;
import com.rydex.events.EventType;
import com.rydex.events.PaymentPayload;
import com.rydex.events.PaymentPayload.Kind;
import com.rydex.events.PaymentPayload.Provider;
import com.rydex.events.PaymentPayload.Status;
import com.rydex.events.Topics;
import com.rydex.ride.IntegrationTest;
import com.rydex.ride.geo.RouteResult;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** payment-service's outcomes show on the ride (payment.events consumer). */
class PaymentOutcomeTests extends IntegrationTest {

    static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void stubRouting() {
        given(osrm.route(any(), any())).willReturn(List.of(new RouteResult.Alternative(5_000, 900, "poly")));
    }

    private record Booked(UUID rideId, String rider) {
    }

    private Booked book() {
        String rider = token(api().register("RIDER"));
        var res = api().call(HttpMethod.POST, "/api/rides", rider,
                Map.of("pickup", Map.of("lat", 13.0827, "lng", 80.2707), "drop", Map.of("lat", 13.05, "lng", 80.2824),
                        "rideType", "ECONOMY"),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        return new Booked(UUID.fromString(res.getBody().path("id").asString()), rider);
    }

    private EventEnvelope<PaymentPayload> publish(EventType type, UUID rideId, Status status, String refunded) {
        var payload = new PaymentPayload(UUID.randomUUID(), UUID.randomUUID(), null, Kind.RIDE_FARE,
                new BigDecimal("123.00"), new BigDecimal(refunded), null, "INR", Provider.MOCK, "mock_1", status, null);
        var event = EventEnvelope.of(type, rideId.toString(), payload);
        kafka().publish(Topics.PAYMENT_EVENTS, event.rideId(), json.writeValueAsString(event), type.name());
        return event;
    }

    private String paymentStatus(Booked b) {
        return api().get("/api/rides/" + b.rideId(), b.rider()).getBody().path("paymentStatus").asString(null);
    }

    @Test
    void theRideShowsItsPaymentStatus() {
        Booked b = book();
        assertThat(paymentStatus(b)).isNull();

        publish(EventType.PAYMENT_SUCCEEDED, b.rideId(), Status.SUCCEEDED, "0");
        await().atMost(WAIT).until(() -> "SUCCEEDED".equals(paymentStatus(b)));

        publish(EventType.PAYMENT_REFUNDED, b.rideId(), Status.REFUNDED, "123.00");
        await().atMost(WAIT).until(() -> "REFUNDED".equals(paymentStatus(b)));
        assertThat(api().get("/api/rides/" + b.rideId(), b.rider()).getBody().path("refundedAmount").decimalValue())
                .isEqualByComparingTo("123");
    }

    @Test
    void eventsAreAppliedOnceAndUnknownRidesAreIgnored() {
        Booked b = book();
        var succeeded = publish(EventType.PAYMENT_SUCCEEDED, b.rideId(), Status.SUCCEEDED, "0");
        await().atMost(WAIT).until(() -> "SUCCEEDED".equals(paymentStatus(b)));

        // a redelivery must not undo a later change
        publish(EventType.PAYMENT_REFUNDED, b.rideId(), Status.REFUNDED, "123.00");
        await().atMost(WAIT).until(() -> "REFUNDED".equals(paymentStatus(b)));
        kafka().publish(Topics.PAYMENT_EVENTS, succeeded.rideId(), json.writeValueAsString(succeeded),
                "PAYMENT_SUCCEEDED");
        // same ride, same partition: once this one is handled, so is the redelivery before it
        var marker = publish(EventType.PAYMENT_REFUNDED, b.rideId(), Status.REFUNDED, "123.00");
        await().atMost(WAIT).until(() -> processed(marker.eventId()));
        assertThat(paymentStatus(b)).isEqualTo("REFUNDED");

        var unknown = publish(EventType.PAYMENT_SUCCEEDED, UUID.randomUUID(), Status.SUCCEEDED, "0");
        await().atMost(WAIT).until(() -> processed(unknown.eventId()));
    }

    private boolean processed(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id = :id")
                .param("id", eventId).query(Integer.class).single() == 1;
    }
}
