package com.rydex.messaging;

import com.rydex.events.EventEnvelope;
import com.rydex.events.EventType;
import com.rydex.events.PaymentPayload;
import com.rydex.events.RidePayload;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Parses Kafka message values back into envelopes. Malformed input fails fast as non-retryable. */
public class EventCodec {

    private static final TypeReference<EventEnvelope<RidePayload>> RIDE_EVENT = new TypeReference<>() {
    };
    private static final TypeReference<EventEnvelope<PaymentPayload>> PAYMENT_EVENT = new TypeReference<>() {
    };

    private final JsonMapper json;

    public EventCodec(JsonMapper json) {
        this.json = json;
    }

    /**
     * Cheap pre-filter: false when the record's {@code eventType} header (set by {@link OutboxRelay})
     * names a type outside {@code wanted}, so the consumer can skip it without parsing. Records
     * without the header are parsed, to be safe.
     */
    public static boolean mayBeOneOf(ConsumerRecord<?, ?> record, Set<EventType> wanted) {
        Header header = record.headers().lastHeader("eventType");
        if (header == null) {
            return true;
        }
        String type = new String(header.value(), StandardCharsets.UTF_8);
        return wanted.stream().anyMatch(t -> t.name().equals(type));
    }

    public EventEnvelope<RidePayload> rideEvent(String value) {
        return read(value, RIDE_EVENT, "ride");
    }

    public EventEnvelope<PaymentPayload> paymentEvent(String value) {
        return read(value, PAYMENT_EVENT, "payment");
    }

    private <T> EventEnvelope<T> read(String value, TypeReference<EventEnvelope<T>> type, String what) {
        if (value == null || value.isBlank()) {
            throw new NonRetryableEventException("empty " + what + " event");
        }
        try {
            return json.readValue(value, type);
        } catch (RuntimeException e) {
            // Jackson parse errors and the envelope's own validation (null id, bad version, ...)
            throw new NonRetryableEventException("unreadable " + what + " event: " + e.getMessage());
        }
    }
}
