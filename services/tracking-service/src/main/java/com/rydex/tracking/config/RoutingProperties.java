package com.rydex.tracking.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** OSRM for live ETAs, with the straight-line fallback used across Rydex. */
@ConfigurationProperties("rydex.routing")
public record RoutingProperties(String osrmBaseUrl, String userAgent, Duration connectTimeout, Duration readTimeout,
                                double fallbackSpeedKmh, double roadFactor) {
}
