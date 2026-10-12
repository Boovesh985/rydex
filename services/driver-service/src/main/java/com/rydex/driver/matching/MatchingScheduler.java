package com.rydex.driver.matching;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Drives matching: every {@code pollInterval} it claims due jobs (new rides, expired offers,
 * searches to retry) and runs one matching step for each on a virtual thread. Claims are
 * leased in Postgres, so several instances can poll without stepping on each other.
 */
@Component
class MatchingScheduler {

    private static final Logger log = LoggerFactory.getLogger(MatchingScheduler.class);
    private static final int BATCH = 50;

    private final DueJobClaimer claimer;
    private final MatchingService matching;
    private final MatchingProperties props;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    MatchingScheduler(DueJobClaimer claimer, MatchingService matching, MatchingProperties props,
                      TaskScheduler scheduler, Clock clock) {
        this.claimer = claimer;
        this.matching = matching;
        this.props = props;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        scheduler.scheduleWithFixedDelay(this::tick, props.pollInterval());
    }

    void tick() {
        try {
            Instant now = clock.instant();
            for (UUID rideId : claimer.claim(now, now.plus(props.lease()), BATCH)) {
                workers.submit(() -> step(rideId));
            }
        } catch (RuntimeException e) {
            log.warn("Matching poll failed: {}", e.toString());
        }
    }

    private void step(UUID rideId) {
        try {
            matching.process(rideId);
        } catch (RuntimeException e) {
            // the lease expires and the job is retried
            log.error("Matching step failed for ride {}", rideId, e);
        }
    }

    @PreDestroy
    void stop() {
        workers.shutdownNow();
    }
}
