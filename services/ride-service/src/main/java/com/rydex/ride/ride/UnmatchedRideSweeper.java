package com.rydex.ride.ride;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Safety net: a ride that is still REQUESTED long after booking never got a matching outcome
 * (driver-service was down for too long, or its event was dead-lettered). Without this the rider
 * would wait forever and could not book again. Such rides are cancelled by the system; the
 * RIDE_CANCELLED event makes driver-service stop matching and free any driver it assigned meanwhile.
 */
@Component
class UnmatchedRideSweeper {

    private static final Logger log = LoggerFactory.getLogger(UnmatchedRideSweeper.class);
    private static final int BATCH = 100;

    private final RideRepository rides;
    private final RideService rideService;
    private final RideProperties props;
    private final TaskScheduler scheduler;
    private final Clock clock;

    UnmatchedRideSweeper(RideRepository rides, RideService rideService, RideProperties props, TaskScheduler scheduler,
                         Clock clock) {
        this.rides = rides;
        this.rideService = rideService;
        this.props = props;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        scheduler.scheduleWithFixedDelay(this::sweepSafely, props.sweepInterval());
    }

    /** Cancels rides REQUESTED for longer than the timeout; returns how many. */
    int sweep() {
        Instant cutoff = clock.instant().minus(props.unmatchedTimeout());
        int expired = 0;
        for (UUID rideId : rides.findIdsByStatusRequestedBefore(RideStatus.REQUESTED, cutoff, PageRequest.of(0, BATCH))) {
            try {
                if (rideService.expireUnmatched(rideId)) {
                    expired++;
                    log.warn("Ride {} had no matching outcome after {}; cancelled", rideId, props.unmatchedTimeout());
                }
            } catch (ObjectOptimisticLockingFailureException e) {
                // an outcome or the rider's cancel landed at the same moment: the ride has moved on
            }
        }
        return expired;
    }

    private void sweepSafely() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.warn("Unmatched-ride sweep failed: {}", e.toString());
        }
    }
}
