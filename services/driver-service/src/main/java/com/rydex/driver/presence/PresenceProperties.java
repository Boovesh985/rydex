package com.rydex.driver.presence;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param staleAfter          a driver silent for longer is dropped from the search indexes
 * @param reapInterval        how often the reaper looks for silent drivers
 * @param minLocationInterval location updates closer together than this are rejected (429)
 * @param reconcileInterval   how often a missing Redis projection is rebuilt from Postgres
 */
@ConfigurationProperties("rydex.presence")
public record PresenceProperties(Duration staleAfter, Duration reapInterval, Duration minLocationInterval,
                                 Duration reconcileInterval) {
}
