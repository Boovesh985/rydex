package com.rydex.tracking.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param historyInterval one stored point per driver per interval while on a trip
 * @param etaRefresh      an OSRM ETA is reused, counting down, for this long before OSRM is asked again
 */
@ConfigurationProperties("rydex.tracking")
public record TrackingProperties(Duration historyInterval, Duration etaRefresh) {
}
