package com.rydex.tracking.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Must match driver-service's {@code rydex.presence}: both services write the same Redis keys.
 *
 * @param staleAfter          a location expires after this much silence
 * @param minLocationInterval location updates closer together than this are dropped (max 2/s)
 */
@ConfigurationProperties("rydex.presence")
public record PresenceProperties(Duration staleAfter, Duration minLocationInterval) {
}
