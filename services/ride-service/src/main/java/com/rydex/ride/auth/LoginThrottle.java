package com.rydex.ride.auth;

import com.rydex.common.RedisKeys;
import com.rydex.ride.web.ApiException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Per-account brute-force protection, on top of the per-IP rate limit: after {@code maxFailures}
 * wrong passwords within the window the account refuses sign-ins until the window ends, from
 * any IP. Unknown emails are counted the same way, so the lockout does not reveal which accounts
 * exist. Fails open if Redis is down (the per-IP limit still applies).
 */
@Component
class LoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    private final StringRedisTemplate redis;
    private final int maxFailures;
    private final Duration window;

    LoginThrottle(StringRedisTemplate redis,
                  @Value("${rydex.auth.lockout.max-failures:10}") int maxFailures,
                  @Value("${rydex.auth.lockout.window:15m}") Duration window) {
        this.redis = redis;
        this.maxFailures = maxFailures;
        this.window = window;
    }

    /** Throws 429 while the account is locked. */
    void check(String emailHash) {
        String key = RedisKeys.loginFailures(emailHash);
        try {
            String value = redis.opsForValue().get(key);
            if (value != null && Integer.parseInt(value) >= maxFailures) {
                Long ttl = redis.getExpire(key);
                throw ApiException.tooManyRequests(
                        "Too many failed sign-ins for this account. Try again later or reset your password.",
                        ttl == null || ttl < 1 ? window.toSeconds() : ttl);
            }
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Login throttle unavailable, allowing sign-in: {}", e.toString());
        }
    }

    void failed(String emailHash) {
        String key = RedisKeys.loginFailures(emailHash);
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, window);
            }
            if (count != null && count == maxFailures) {
                log.warn("Account {}… locked for {} after {} failed sign-ins", emailHash.substring(0, 8), window,
                        maxFailures);
            }
        } catch (RuntimeException e) {
            log.warn("Login throttle unavailable: {}", e.toString());
        }
    }

    void succeeded(String emailHash) {
        try {
            redis.delete(RedisKeys.loginFailures(emailHash));
        } catch (RuntimeException e) {
            log.warn("Login throttle unavailable: {}", e.toString());
        }
    }
}
