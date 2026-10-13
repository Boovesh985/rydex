package com.rydex.driver.presence;

import com.rydex.presence.PresenceStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
class PresenceConfig {

    @Bean
    PresenceStore presenceStore(StringRedisTemplate redis, PresenceProperties props) {
        return new PresenceStore(redis, props.staleAfter(), props.minLocationInterval());
    }
}
