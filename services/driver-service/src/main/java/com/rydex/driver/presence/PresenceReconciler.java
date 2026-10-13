package com.rydex.driver.presence;

import com.rydex.common.DriverStatus;
import com.rydex.driver.driver.Driver;
import com.rydex.driver.driver.DriverRepository;
import com.rydex.presence.PresenceStore;
import java.util.EnumSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Rebuilds the Redis availability projection ({@code driver:{id}:state}) of online drivers when
 * Redis has lost it, e.g. after a flush or failover. driver-service's own REST heartbeat repairs
 * it on the spot, but heartbeats that arrive over tracking-service's WebSocket cannot (tracking has
 * no driver table), so without this an online driver would stay unsearchable.
 *
 * <p>Only missing projections are written. A projection that merely disagrees with Postgres is
 * left to the offer-time check in matching: overwriting it here could race with a status change
 * committed after this read.
 */
@Component
class PresenceReconciler {

    private static final Logger log = LoggerFactory.getLogger(PresenceReconciler.class);

    private final DriverRepository drivers;
    private final PresenceStore presence;
    private final PresenceProperties props;
    private final TaskScheduler scheduler;

    PresenceReconciler(DriverRepository drivers, PresenceStore presence, PresenceProperties props, TaskScheduler scheduler) {
        this.drivers = drivers;
        this.presence = presence;
        this.props = props;
        this.scheduler = scheduler;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        scheduler.scheduleWithFixedDelay(this::reconcileSafely, props.reconcileInterval());
    }

    /** Returns how many projections were rebuilt. */
    int reconcile() {
        int rebuilt = 0;
        for (Driver d : drivers.findByStatusIn(EnumSet.of(DriverStatus.AVAILABLE, DriverStatus.ON_TRIP))) {
            if (presence.state(d.getId()).isEmpty()) {
                presence.setStatus(d.getId(), d.getStatus(), d.getVehicleType());
                rebuilt++;
            }
        }
        if (rebuilt > 0) {
            log.warn("Rebuilt the Redis presence projection of {} online driver(s)", rebuilt);
        }
        return rebuilt;
    }

    private void reconcileSafely() {
        try {
            reconcile();
        } catch (RuntimeException e) {
            log.warn("Presence reconcile failed: {}", e.toString());
        }
    }
}
