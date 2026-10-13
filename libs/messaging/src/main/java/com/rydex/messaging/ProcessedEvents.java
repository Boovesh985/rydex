package com.rydex.messaging;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent consumers: Kafka delivers at least once, so every handler first records the
 * event id here, in the same transaction as its own changes. A redelivered event finds its
 * marker and is skipped; if the handler fails, the marker rolls back with everything else.
 */
public class ProcessedEvents {

    private final JdbcClient jdbc;

    public ProcessedEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** True the first time {@code consumer} sees {@code eventId}; false for a duplicate. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstDelivery(String consumer, UUID eventId) {
        return jdbc.sql("""
                INSERT INTO processed_events (consumer_name, event_id, processed_at)
                VALUES (:consumer, :eventId, now())
                ON CONFLICT DO NOTHING
                """)
                .param("consumer", consumer)
                .param("eventId", eventId)
                .update() == 1;
    }
}
