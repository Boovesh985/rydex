package com.rydex.tracking.location;

import com.rydex.tracking.config.TrackingProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Deletes trip paths older than {@code rydex.tracking.history-retention}. The living city stores about
 * half a million points a day around the clock: kept for ever, they would fill the server's disk within
 * a year. Runs every 10 minutes, in batches so no delete holds locks for long, on its own thread:
 * tracking-service's one task scheduler sends the STOMP heart-beats and must never wait.
 */
@Component
class LocationHistoryRetention implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(LocationHistoryRetention.class);
    static final int BATCH = 5_000;
    /** 10 batches every 10 minutes: up to 300,000 points an hour, far more than the city writes. */
    static final int MAX_BATCHES = 10;

    private final JdbcClient jdbc;
    private final TrackingProperties props;
    private final Clock clock;
    private ScheduledExecutorService executor;

    LocationHistoryRetention(JdbcClient jdbc, TrackingProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    /** Returns how many points were deleted. */
    int purge() {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(props.historyRetention()));
        int total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int deleted = jdbc.sql("""
                    DELETE FROM driver_location_history WHERE id IN (
                        SELECT id FROM driver_location_history WHERE recorded_at < :cutoff LIMIT :batch)
                    """)
                    .param("cutoff", cutoff)
                    .param("batch", BATCH)
                    .update();
            total += deleted;
            if (deleted < BATCH) {
                break;
            }
        }
        if (total > 0) {
            log.info("Deleted {} trip path points older than {}", total, props.historyRetention());
        }
        return total;
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "history-retention");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                purge();
            } catch (RuntimeException e) {
                log.warn("Trip path cleanup failed, trying again in 10 minutes: {}", e.toString());
            }
        }, 1, 10, TimeUnit.MINUTES);
    }

    @Override
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }
}
