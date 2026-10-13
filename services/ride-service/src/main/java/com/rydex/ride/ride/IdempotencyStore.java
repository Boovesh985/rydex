package com.rydex.ride.ride;

import com.rydex.common.RedisKeys;
import com.rydex.ride.web.ApiException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotency-Key support for POST /rides. A key is claimed with SET NX, then bound to
 * the created ride id. A retry with the same key returns the original ride.
 *
 * <p>The claim itself is short-lived: if this instance dies mid-booking, the key frees up after
 * a minute instead of answering "still being processed" for a day.
 */
@Component
class IdempotencyStore {

    private static final String PENDING = "PENDING";
    private static final Duration TTL = Duration.ofHours(24);
    /** Far longer than a booking takes (routing times out after 8 s). */
    static final Duration PENDING_TTL = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;

    IdempotencyStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Empty if the key was claimed by this call; otherwise the ride created by the earlier call. */
    Optional<UUID> begin(UUID userId, String key) {
        String redisKey = RedisKeys.idempotency(userId, key);
        if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(redisKey, PENDING, PENDING_TTL))) {
            return Optional.empty();
        }
        String value = redis.opsForValue().get(redisKey);
        if (value == null) {
            // expired between the two calls; treat as a fresh key
            return begin(userId, key);
        }
        if (PENDING.equals(value)) {
            throw ApiException.conflict("A request with this Idempotency-Key is still being processed");
        }
        return Optional.of(UUID.fromString(value));
    }

    void complete(UUID userId, String key, UUID rideId) {
        redis.opsForValue().set(RedisKeys.idempotency(userId, key), rideId.toString(), TTL);
    }

    void abort(UUID userId, String key) {
        redis.delete(RedisKeys.idempotency(userId, key));
    }
}
