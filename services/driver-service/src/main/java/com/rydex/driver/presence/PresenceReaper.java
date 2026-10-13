package com.rydex.driver.presence;

import com.rydex.presence.PresenceStore;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * GEO set members never expire on their own, so drivers that stop sending heartbeats
 * (app killed, no signal) are removed from the search indexes here. Their status is left
 * alone: the next heartbeat makes them searchable again.
 */
@Component
class PresenceReaper {

    private static final Logger log = LoggerFactory.getLogger(PresenceReaper.class);
    private static final int BATCH = 500;

    private final PresenceStore presence;
    private final PresenceProperties props;
    private final TaskScheduler scheduler;
    private final Clock clock;

    PresenceReaper(PresenceStore presence, PresenceProperties props, TaskScheduler scheduler, Clock clock) {
        this.presence = presence;
        this.props = props;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        scheduler.scheduleWithFixedDelay(this::reapSafely, props.reapInterval());
    }

    /** Evicts every driver silent for longer than {@code staleAfter}; returns how many were evicted. */
    int reap() {
        Instant cutoff = clock.instant().minus(props.staleAfter());
        int evicted = 0;
        for (UUID id : presence.silentSince(cutoff, BATCH)) {
            if (presence.evict(id, cutoff)) {
                evicted++;
            }
        }
        if (evicted > 0) {
            log.info("Evicted {} silent driver(s) from the search indexes", evicted);
        }
        return evicted;
    }

    private void reapSafely() {
        try {
            reap();
        } catch (RuntimeException e) {
            log.warn("Presence reaper failed: {}", e.toString());
        }
    }
}
