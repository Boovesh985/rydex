package com.rydex.ride.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The internal ai-service (smart ride suggestions, master prompt §9).
 *
 * @param enabled false: estimates never carry a suggestion
 * @param timeout the whole call; a slower answer is dropped and the estimate goes out without one
 */
@ConfigurationProperties("rydex.ai")
public record AiProperties(boolean enabled, String baseUrl, Duration connectTimeout, Duration timeout) {
}
