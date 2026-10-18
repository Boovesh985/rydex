package com.rydex.ride.ride;

import com.rydex.common.RideType;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param unmatchedTimeout a ride still REQUESTED this long after booking is cancelled by the system:
 *                         matching gives up after about 2 minutes, so no outcome by then means it was lost
 * @param sweepInterval    how often the unmatched- and abandoned-ride sweepers run
 * @param abandonedAfter   a ride under way (driver assigned, arrived or driving) whose driver has sent no
 *                         heartbeat for this long, and that has not moved on for as long, is ended by the
 *                         system, free of charge
 * @param unheardAfter     the same for a driver with no heartbeat on record at all (from before driver:heard
 *                         existed, or a record lost with Redis): judged by the ride's own last step, later
 * @param cancellationFees INR a rider pays for cancelling after the driver arrived, per ride type
 *                         (a type without an entry cancels free)
 * @param arrivalRadiusM   how close to the pickup (metres) a driver's last position must be to mark arrival
 */
@ConfigurationProperties("rydex.rides")
public record RideProperties(
        @DefaultValue("5m") Duration unmatchedTimeout,
        @DefaultValue("30s") Duration sweepInterval,
        @DefaultValue("15m") Duration abandonedAfter,
        @DefaultValue("2h") Duration unheardAfter,
        Map<RideType, BigDecimal> cancellationFees,
        @DefaultValue("250") int arrivalRadiusM) {

    public RideProperties {
        cancellationFees = cancellationFees == null ? Map.of() : Map.copyOf(cancellationFees);
    }

    /** Null when that ride type cancels free. */
    public BigDecimal cancellationFee(RideType type) {
        return cancellationFees.get(type);
    }
}
