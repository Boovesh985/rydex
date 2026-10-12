package com.rydex.driver.matching;

import com.rydex.common.RedisKeys;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * {@code driver:{id}:lock} ({@code SET NX PX}): while a driver holds an offer for one ride no
 * other ride can offer to them, even when two matching jobs pick the same nearest driver at
 * the same moment. The lock value is the ride id, so only the owner can release it.
 */
@Component
public class DriverLocks {

    private final StringRedisTemplate redis;
    private final MatchingProperties props;
    private final DefaultRedisScript<Long> releaseScript;

    public DriverLocks(StringRedisTemplate redis, MatchingProperties props) {
        this.redis = redis;
        this.props = props;
        this.releaseScript = new DefaultRedisScript<>();
        releaseScript.setLocation(new ClassPathResource("redis/release-lock.lua"));
        releaseScript.setResultType(Long.class);
    }

    public boolean tryLock(UUID driverId, UUID rideId) {
        Boolean ok = redis.opsForValue().setIfAbsent(RedisKeys.driverLock(driverId), rideId.toString(), props.driverLockTtl());
        return Boolean.TRUE.equals(ok);
    }

    public void release(UUID driverId, UUID rideId) {
        redis.execute(releaseScript, List.of(RedisKeys.driverLock(driverId)), rideId.toString());
    }

    /** Ride id the driver is locked for, if any. */
    public String holder(UUID driverId) {
        return redis.opsForValue().get(RedisKeys.driverLock(driverId));
    }
}
