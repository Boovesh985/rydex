package com.rydex.tracking.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Base URLs of the services tracking-service calls on behalf of a connected user. */
@ConfigurationProperties("rydex.services")
public record ServicesProperties(String rideServiceUrl, String driverServiceUrl, Duration connectTimeout,
                                 Duration readTimeout) {
}
