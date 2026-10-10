package com.rydex.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class EventEnvelopeTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-12T10:45:00Z"), ZoneOffset.UTC);

    @Test
    void factoryFillsIdTimestampAndVersion() {
        var event = EventEnvelope.of(EventType.RIDE_REQUESTED, "RD12345", Map.of("riderId", "U1001"), clock);

        assertThat(event.eventId()).isNotNull();
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-01-12T10:45:00Z"));
        assertThat(event.version()).isEqualTo(EventEnvelope.CURRENT_VERSION);
        assertThat(event.key()).isEqualTo("RD12345");
        assertThat(event.topic()).isEqualTo(Topics.RIDE_EVENTS);
    }

    @Test
    void paymentEventsGoToPaymentTopic() {
        var event = EventEnvelope.of(EventType.PAYMENT_SUCCEEDED, "RD1", Map.of(), clock);

        assertThat(event.topic()).isEqualTo(Topics.PAYMENT_EVENTS);
        assertThat(Topics.deadLetter(event.topic())).isEqualTo("payment.events.DLT");
    }

    @Test
    void rejectsMissingRideId() {
        assertThatThrownBy(() -> EventEnvelope.of(EventType.RIDE_STARTED, null, Map.of(), clock))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rideId");
    }

    @Test
    void roundTripsThroughJson() {
        var mapper = JsonMapper.builder().build();
        var event = EventEnvelope.of(EventType.DRIVER_ASSIGNED, "RD12345", Map.of("driverId", "D5678"), clock);

        String json = mapper.writeValueAsString(event);
        EventEnvelope<Map<String, String>> back = mapper.readValue(json, new TypeReference<>() {});

        assertThat(json).contains("\"eventType\":\"DRIVER_ASSIGNED\"").contains("\"occurredAt\":\"2026-01-12T10:45:00Z\"");
        assertThat(back).isEqualTo(event);
    }
}
