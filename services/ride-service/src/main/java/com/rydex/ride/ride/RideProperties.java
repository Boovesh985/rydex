package com.rydex.ride.ride;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param unmatchedTimeout a ride still REQUESTED this long after booking is cancelled by the system:
 *                         matching gives up after about 2 minutes, so no outcome by then means it was lost
 * @param sweepInterval    how often the unmatched-ride sweeper runs
 */
@ConfigurationProperties("rydex.rides")
public record RideProperties(
        @DefaultValue("5m") Duration unmatchedTimeout,
        @DefaultValue("30s") Duration sweepInterval) {
}
