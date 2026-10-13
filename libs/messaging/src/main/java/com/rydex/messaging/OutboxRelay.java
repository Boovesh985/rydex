package com.rydex.messaging;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes outbox rows to Kafka (master prompt §8: poll every 500 ms, mark published).
 *
 * <p>Only one relay per service publishes at a time: each pass first takes a transaction-scoped
 * Postgres advisory lock named after the service's schema, and a pass that cannot get it does
 * nothing. With two relays sharing the work, one could publish a ride's second event while the
 * other still held its first, so the lock is what keeps per-ride order across instances.
 * Records are sent one at a time in {@code seq} order and each send waits for the broker's
 * acknowledgement: if a send fails the pass stops there, so events of one ride are never
 * published out of order. Delivery is at-least-once (a crash between send and commit
 * republishes); consumers deduplicate by event id.
 */
public class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /**
     * Advisory locks are database-wide and every service has its own schema in the one database,
     * so the key is derived from the schema: each service's relays exclude only each other.
     */
    static final String LOCK_SQL = "SELECT pg_try_advisory_xact_lock(hashtext(current_schema() || '.outbox-relay'))";

    private record Row(UUID id, String topic, String key, String eventType, String payload) {
    }

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final MessagingProperties.Outbox props;
    private final Clock clock;
    private ScheduledExecutorService executor;
    private volatile boolean running;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                       MessagingProperties.Outbox props, Clock clock) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    /** Publishes up to one batch; returns how many rows were published. */
    public int relayOnce() {
        Integer published = tx.execute(status -> {
            if (!Boolean.TRUE.equals(jdbc.sql(LOCK_SQL).query(Boolean.class).single())) {
                return 0; // another instance is relaying this service's outbox
            }
            List<Row> rows = jdbc.sql("""
                    SELECT id, topic, msg_key, event_type, payload::text AS payload FROM outbox
                    WHERE published_at IS NULL
                    ORDER BY seq
                    LIMIT :limit
                    """)
                    .param("limit", props.batchSize())
                    .query((rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("topic"),
                            rs.getString("msg_key"), rs.getString("event_type"), rs.getString("payload")))
                    .list();
            List<UUID> sent = new ArrayList<>(rows.size());
            for (Row row : rows) {
                if (!send(row)) {
                    break;
                }
                sent.add(row.id());
            }
            if (!sent.isEmpty()) {
                jdbc.sql("UPDATE outbox SET published_at = :now WHERE id IN (:ids)")
                        .param("now", Timestamp.from(clock.instant()))
                        .param("ids", sent)
                        .update();
            }
            return sent.size();
        });
        return published == null ? 0 : published;
    }

    private boolean send(Row row) {
        var record = new ProducerRecord<>(row.topic(), row.key(), row.payload());
        record.headers().add("eventId", row.id().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", row.eventType().getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(record).get(props.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("Outbox relay could not publish {} {} to {}: {}", row.eventType(), row.id(), row.topic(), e.toString());
            return false;
        }
    }

    /** Deletes published rows and processed-event markers older than the retention period. */
    public void cleanUp() {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(props.retention()));
        int outbox = jdbc.sql("DELETE FROM outbox WHERE published_at < :cutoff").param("cutoff", cutoff).update();
        int markers = jdbc.sql("DELETE FROM processed_events WHERE processed_at < :cutoff").param("cutoff", cutoff).update();
        if (outbox + markers > 0) {
            log.info("Messaging cleanup removed {} outbox row(s) and {} processed-event marker(s)", outbox, markers);
        }
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-relay");
            t.setDaemon(true);
            return t;
        });
        long poll = props.pollInterval().toMillis();
        executor.scheduleWithFixedDelay(() -> safely("relay", this::drain), poll, poll, TimeUnit.MILLISECONDS);
        long cleanup = props.cleanupInterval().toMillis();
        executor.scheduleWithFixedDelay(() -> safely("cleanup", this::cleanUp), cleanup, cleanup, TimeUnit.MILLISECONDS);
        running = true;
    }

    /** Keeps relaying while full batches come back, so a backlog drains without waiting a poll per batch. */
    private void drain() {
        while (running && relayOnce() == props.batchSize()) {
            // next batch
        }
    }

    private static void safely(String what, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.warn("Outbox {} failed: {}", what, e.toString());
        }
    }

    @Override
    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Start after everything else (Kafka listeners included) and stop first. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
