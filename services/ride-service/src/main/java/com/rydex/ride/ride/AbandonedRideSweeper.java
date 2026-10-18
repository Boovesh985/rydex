package com.rydex.ride.ride;

import com.rydex.presence.PresenceStore;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Safety net for rides under way whose driver vanished: the app closed for good, a phone lost for
 * the day, the simulator stopped mid-trip. Nothing else would ever end such a ride (only its driver
 * moves it on), so the rider could not book again and the ride would count as in flight forever. A
 * ride that has not moved on for {@code rydex.rides.abandoned-after} and whose driver has sent no
 * heartbeat for as long (driver:heard, written by driver-service and tracking-service) is ended
 * by the system, free of charge.
 */
@Component
class AbandonedRideSweeper {

    private static final Logger log = LoggerFactory.getLogger(AbandonedRideSweeper.class);
    private static final int BATCH = 100;

    private final RideRepository rides;
    private final RideService rideService;
    private final PresenceStore presence;
    private final RideProperties props;
    private final TaskScheduler scheduler;
    private final Clock clock;

    AbandonedRideSweeper(RideRepository rides, RideService rideService, PresenceStore presence, RideProperties props,
                         TaskScheduler scheduler, Clock clock) {
        this.rides = rides;
        this.rideService = rideService;
        this.presence = presence;
        this.props = props;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        scheduler.scheduleWithFixedDelay(this::sweepSafely, props.sweepInterval());
    }

    /** Ends rides under way whose driver went silent; returns how many. */
    int sweep() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(props.abandonedAfter());
        Instant unheardCutoff = now.minus(props.unheardAfter());
        int ended = 0;
        for (Ride ride : rides.findUnderWaySince(RideService.UNDER_WAY, cutoff, PageRequest.of(0, BATCH))) {
            Instant heard = ride.getDriverId() == null ? null : presence.lastHeard(ride.getDriverId()).orElse(null);
            // no heartbeat on record says nothing about how long the driver has been gone (the record
            // may predate driver:heard): then only a ride that has stood still for much longer is ended
            boolean silent = heard != null ? heard.isBefore(cutoff) : lastStep(ride).isBefore(unheardCutoff);
            if (!silent) {
                continue; // a long trip, a long wait at the pickup: the driver is still there
            }
            try {
                if (rideService.endAbandoned(ride.getId(), ride.getStatus())) {
                    ended++;
                    log.warn("Ride {} ({}) ended: driver {} silent for over {}", ride.getId(), ride.getStatus(),
                            ride.getDriverId(), props.abandonedAfter());
                }
            } catch (ObjectOptimisticLockingFailureException e) {
                // the driver came back and moved the ride on at the same moment
            }
        }
        return ended;
    }

    private static Instant lastStep(Ride ride) {
        Instant at = ride.getStartedAt() != null ? ride.getStartedAt() : ride.getArrivedAt();
        return at != null ? at : ride.getAssignedAt();
    }

    private void sweepSafely() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.warn("Abandoned-ride sweep failed: {}", e.toString());
        }
    }
}
