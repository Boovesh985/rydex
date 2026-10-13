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
 * @param sweepInterval    how often the unmatched-ride sweeper runs
 * @param cancellationFees INR a rider pays for cancelling after the driver arrived, per ride type
 *                         (a type without an entry cancels free)
 */
@ConfigurationProperties("rydex.rides")
public record RideProperties(
        @DefaultValue("5m") Duration unmatchedTimeout,
        @DefaultValue("30s") Duration sweepInterval,
        Map<RideType, BigDecimal> cancellationFees) {

    public RideProperties {
        cancellationFees = cancellationFees == null ? Map.of() : Map.copyOf(cancellationFees);
    }

    /** Null when that ride type cancels free. */
    public BigDecimal cancellationFee(RideType type) {
        return cancellationFees.get(type);
    }
}
