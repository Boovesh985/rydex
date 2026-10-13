package com.rydex.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rydex.common.RideType;
import com.rydex.common.geo.GeoPoint;
import com.rydex.events.EventEnvelope;
import com.rydex.events.EventType;
import com.rydex.events.PaymentPayload;
import com.rydex.events.RidePayload;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EventCodecTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final EventCodec codec = new EventCodec(json);

    @Test
    void roundTripsARideEvent() {
        var payload = new RidePayload(UUID.randomUUID(), UUID.randomUUID(), RideType.COMFORT,
                new GeoPoint(13.0827, 80.2707, "Chennai Central"), GeoPoint.of(12.9941, 80.1709),
                new BigDecimal("459.00"), 22111, 1359, null);
        var event = EventEnvelope.of(EventType.DRIVER_ASSIGNED, UUID.randomUUID().toString(), payload);

        var read = codec.rideEvent(json.writeValueAsString(event));

        assertThat(read).isEqualTo(event);
    }

    @Test
    void roundTripsAChargedCancellation() {
        var payload = new RidePayload(UUID.randomUUID(), UUID.randomUUID(), RideType.ECONOMY,
                GeoPoint.of(13.0827, 80.2707), GeoPoint.of(12.9941, 80.1709), new BigDecimal("339.00"), 22111, 1359,
                "Changed plans", new BigDecimal("50.00"));
        var event = EventEnvelope.of(EventType.RIDE_CANCELLED, UUID.randomUUID().toString(), payload);

        var read = codec.rideEvent(json.writeValueAsString(event));

        assertThat(read).isEqualTo(event);
        assertThat(read.payload().cancellationFee()).isEqualByComparingTo("50");
    }

    @Test
    void eventsFromBeforeCancellationFeesStillRead() {
        String value = """
                {"eventId":"%s","eventType":"RIDE_CANCELLED","version":1,"occurredAt":"2026-10-13T10:00:00Z",
                 "rideId":"r1","payload":{"riderId":null,"fare":339.00,"reason":"x"}}
                """.formatted(UUID.randomUUID());

        assertThat(codec.rideEvent(value).payload().cancellationFee()).isNull();
    }

    @Test
    void roundTripsAPaymentEvent() {
        var payload = new PaymentPayload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                PaymentPayload.Kind.RIDE_FARE, new BigDecimal("339.00"), new BigDecimal("100.00"),
                new BigDecimal("100.00"), "INR", PaymentPayload.Provider.RAZORPAY, "order_123",
                PaymentPayload.Status.SUCCEEDED, "Goodwill");
        var event = EventEnvelope.of(EventType.PAYMENT_REFUNDED, UUID.randomUUID().toString(), payload);

        assertThat(codec.paymentEvent(json.writeValueAsString(event))).isEqualTo(event);
        assertThatThrownBy(() -> codec.paymentEvent("{oops")).isInstanceOf(NonRetryableEventException.class)
                .hasMessageContaining("payment event");
    }

    @Test
    void ignoresFieldsAddedByNewerProducers() {
        String value = """
                {"eventId":"%s","eventType":"RIDE_CANCELLED","version":1,"occurredAt":"2026-10-13T10:00:00Z",
                 "rideId":"r1","payload":{"riderId":null,"reason":"x","newField":42},"traceId":"abc"}
                """.formatted(UUID.randomUUID());

        var read = codec.rideEvent(value);

        assertThat(read.eventType()).isEqualTo(EventType.RIDE_CANCELLED);
        assertThat(read.payload().reason()).isEqualTo("x");
    }

    @Test
    void malformedEventsAreNotRetryable() {
        assertThatThrownBy(() -> codec.rideEvent("{not json")).isInstanceOf(NonRetryableEventException.class);
        assertThatThrownBy(() -> codec.rideEvent("")).isInstanceOf(NonRetryableEventException.class);
        assertThatThrownBy(() -> codec.rideEvent(null)).isInstanceOf(NonRetryableEventException.class);
        // the envelope rejects a missing event id
        assertThatThrownBy(() -> codec.rideEvent("""
                {"eventType":"RIDE_CANCELLED","version":1,"occurredAt":"2026-10-13T10:00:00Z","rideId":"r1"}
                """)).isInstanceOf(NonRetryableEventException.class);
        assertThatThrownBy(() -> codec.rideEvent("""
                {"eventId":"%s","eventType":"TELEPORTED","version":1,"occurredAt":"2026-10-13T10:00:00Z","rideId":"r1"}
                """.formatted(UUID.randomUUID()))).isInstanceOf(NonRetryableEventException.class);
    }

    @Test
    void headerPrefilterSkipsUnwantedTypes() {
        var wanted = EnumSet.of(EventType.RIDE_REQUESTED);

        assertThat(EventCodec.mayBeOneOf(record("RIDE_REQUESTED"), wanted)).isTrue();
        assertThat(EventCodec.mayBeOneOf(record("RIDE_STARTED"), wanted)).isFalse();
        assertThat(EventCodec.mayBeOneOf(record(null), wanted)).isTrue();
    }

    private static ConsumerRecord<String, String> record(String eventType) {
        var r = new ConsumerRecord<String, String>("ride.events", 0, 0, "k", "v");
        if (eventType != null) {
            r.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        }
        return r;
    }
}
