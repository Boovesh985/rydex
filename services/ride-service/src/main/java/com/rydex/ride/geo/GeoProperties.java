package com.rydex.ride.geo;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Free OSM-based routing (OSRM) and geocoding (Nominatim).
 *
 * @param userAgent          Nominatim's usage policy requires an identifying User-Agent
 * @param countryCodes       restricts place search, e.g. {@code in}
 * @param nominatimInterval  minimum gap between Nominatim calls across all instances (policy: 1 req/s)
 * @param fallbackSpeedKmh   average city speed used when OSRM is unavailable
 * @param roadFactor         straight-line to road distance multiplier for the fallback
 */
@ConfigurationProperties("rydex.geo")
public record GeoProperties(
        String osrmBaseUrl,
        String nominatimBaseUrl,
        String userAgent,
        String countryCodes,
        Duration connectTimeout,
        Duration readTimeout,
        Duration routeCacheTtl,
        Duration placesCacheTtl,
        Duration nominatimInterval,
        double fallbackSpeedKmh,
        double roadFactor) {
}
