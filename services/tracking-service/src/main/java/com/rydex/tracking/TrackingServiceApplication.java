package com.rydex.tracking;

import com.rydex.messaging.EnableMessaging;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * Holds every WebSocket connection (ADR 0011): ingests driver locations, broadcasts them to the
 * rider of the ride, pushes ride notifications and driver offers, and keeps a projection of
 * active rides from {@code ride.events}.
 */
@SpringBootApplication
@EnableMessaging
@ConfigurationPropertiesScan
public class TrackingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrackingServiceApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
