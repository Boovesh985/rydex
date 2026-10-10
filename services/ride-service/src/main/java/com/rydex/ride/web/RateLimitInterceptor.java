package com.rydex.ride.web;

import com.rydex.common.RedisKeys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Fixed-window (1 minute) rate limiting in Redis, per user (or per IP when anonymous)
 * and per route group. Fails open if Redis is unreachable: availability over strictness.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;
    private final RateLimitProperties props;
    private final Clock clock;

    public RateLimitInterceptor(StringRedisTemplate redis, RateLimitProperties props, Clock clock) {
        this.redis = redis;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!props.enabled() || "OPTIONS".equals(request.getMethod())) {
            return true;
        }
        String group = group(request);
        int limit = props.perMinute().getOrDefault(group, props.perMinute().getOrDefault("default", 300));
        long epochSeconds = clock.instant().getEpochSecond();
        long window = epochSeconds / WINDOW.toSeconds();
        String key = RedisKeys.rateLimit(subject(request), group, window);
        Long count;
        try {
            count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, WINDOW.plusSeconds(5));
            }
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable, allowing request: {}", e.toString());
            return true;
        }
        if (count != null && count > limit) {
            long retryAfter = WINDOW.toSeconds() - (epochSeconds % WINDOW.toSeconds());
            throw ApiException.tooManyRequests("Rate limit of " + limit + " requests/minute exceeded", retryAfter);
        }
        return true;
    }

    static String group(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path.startsWith("/api/auth/")) {
            return "auth";
        }
        if (path.startsWith("/api/places/")) {
            return "places";
        }
        if ("POST".equals(method) && path.equals("/api/rides/estimate")) {
            return "estimate";
        }
        if ("POST".equals(method) && path.equals("/api/rides")) {
            return "book";
        }
        return "default";
    }

    private static String subject(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        return "ip:" + request.getRemoteAddr();
    }

    @ConfigurationProperties("rydex.rate-limits")
    public record RateLimitProperties(boolean enabled, Map<String, Integer> perMinute) {
    }
}
