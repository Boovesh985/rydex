package com.rydex.driver.matching;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Matching knobs (master prompt §7).
 *
 * @param searchRadiiKm       GEOSEARCH radii tried in order until someone is found
 * @param candidatesPerSearch how many nearest drivers are ranked by ETA
 * @param offerTimeout        how long a driver has to accept
 * @param driverLockTtl       lock that stops a driver getting two offers at once; longer than the offer
 * @param maxAttempts         offers per ride before NO_DRIVER_FOUND
 * @param searchTimeout       total time to keep looking when nobody nearby is free
 * @param retryInterval       pause between searches that found nobody
 * @param pollInterval        how often the scheduler looks for due jobs
 * @param lease               a claimed job is hidden from other pollers this long (crash safety)
 */
@ConfigurationProperties("rydex.matching")
public record MatchingProperties(
        List<Double> searchRadiiKm,
        int candidatesPerSearch,
        Duration offerTimeout,
        Duration driverLockTtl,
        int maxAttempts,
        Duration searchTimeout,
        Duration retryInterval,
        Duration pollInterval,
        Duration lease) {
}
