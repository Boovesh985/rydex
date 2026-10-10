package com.rydex.ride.outbox;

import com.rydex.events.EventEnvelope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Appends events to the transactional outbox. Must run inside the transaction that
 * changes the aggregate, so the event exists if and only if the change committed.
 */
@Component
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OutboxWriter(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(EventEnvelope<?> event) {
        jdbc.sql("""
                INSERT INTO outbox (id, aggregate_id, topic, msg_key, event_type, payload, created_at)
                VALUES (:id, :aggregateId, :topic, :key, :type, CAST(:payload AS jsonb), :createdAt)
                """)
                .param("id", event.eventId())
                .param("aggregateId", event.rideId())
                .param("topic", event.topic())
                .param("key", event.key())
                .param("type", event.eventType().name())
                .param("payload", json.writeValueAsString(event))
                .param("createdAt", java.sql.Timestamp.from(event.occurredAt()))
                .update();
    }
}
