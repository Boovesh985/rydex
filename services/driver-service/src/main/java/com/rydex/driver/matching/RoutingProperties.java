package com.rydex.driver.matching;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param fallbackSpeedKmh average city speed for straight-line ETAs when OSRM is unavailable
 * @param roadFactor       road distance / straight-line distance, for the same fallback
 */
@ConfigurationProperties("rydex.routing")
public record RoutingProperties(
        String osrmBaseUrl,
        String userAgent,
        Duration connectTimeout,
        Duration readTimeout,
        double fallbackSpeedKmh,
        double roadFactor) {
}
