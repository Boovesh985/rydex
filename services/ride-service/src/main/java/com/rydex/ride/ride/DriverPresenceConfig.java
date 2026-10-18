package com.rydex.ride.ride;

import com.rydex.presence.PresenceStore;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
class DriverPresenceConfig {

    /**
     * Driver presence, read-only here: ride-service only asks when a driver was last heard from (the
     * abandoned-ride sweeper). driver-service and tracking-service write it, so the write settings
     * passed here are never used.
     */
    @Bean
    PresenceStore presenceStore(StringRedisTemplate redis) {
        return new PresenceStore(redis, Duration.ofSeconds(30), Duration.ofMillis(500));
    }
}
