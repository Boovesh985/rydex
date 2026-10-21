package com.rydex.tracking.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param historyInterval one stored point per driver per interval while on a trip
 * @param etaRefresh      an OSRM ETA is reused, counting down, for this long before OSRM is asked again
 * @param historyRetention trip paths older than this are deleted
 */
@ConfigurationProperties("rydex.tracking")
public record TrackingProperties(Duration historyInterval, Duration etaRefresh, Duration historyRetention) {
}
