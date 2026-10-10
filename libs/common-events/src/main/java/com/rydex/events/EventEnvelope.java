package com.rydex.events;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Wire format for every Kafka event. The JSON Schema lives in
 * contracts/events/event-envelope.schema.json and must be kept in sync.
 *
 * @param eventId    unique id, used by consumers for idempotency
 * @param eventType  what happened
 * @param version    payload schema version for {@code eventType}
 * @param occurredAt when it happened (UTC)
 * @param rideId     the ride this event belongs to; also the Kafka message key
 * @param payload    event-specific body
 */
public record EventEnvelope<T>(
        UUID eventId,
        EventType eventType,
        int version,
        Instant occurredAt,
        String rideId,
        T payload) {

    public static final int CURRENT_VERSION = 1;

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(rideId, "rideId");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
    }

    public static <T> EventEnvelope<T> of(EventType type, String rideId, T payload) {
        return of(type, rideId, payload, Clock.systemUTC());
    }

    public static <T> EventEnvelope<T> of(EventType type, String rideId, T payload, Clock clock) {
        return new EventEnvelope<>(UUID.randomUUID(), type, CURRENT_VERSION, clock.instant(), rideId, payload);
    }

    /** Topic this event is published to. */
    public String topic() {
        return eventType.topic();
    }

    /** Kafka message key: keeps all events of one ride ordered on one partition. */
    public String key() {
        return rideId;
    }
}
