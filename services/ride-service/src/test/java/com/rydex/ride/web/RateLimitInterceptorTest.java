package com.rydex.ride.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.rydex.ride.web.RateLimitInterceptor.RateLimitProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitInterceptorTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-12T10:45:20Z"), ZoneOffset.UTC);
    private final RateLimitInterceptor interceptor = new RateLimitInterceptor(redis,
            new RateLimitProperties(true, Map.of("book", 2, "default", 100)), clock);

    @Test
    void groupsRequestsByRoute() {
        assertThat(RateLimitInterceptor.group(req("POST", "/api/auth/login"))).isEqualTo("auth");
        assertThat(RateLimitInterceptor.group(req("GET", "/api/places/search"))).isEqualTo("places");
        assertThat(RateLimitInterceptor.group(req("POST", "/api/rides/estimate"))).isEqualTo("estimate");
        assertThat(RateLimitInterceptor.group(req("POST", "/api/rides"))).isEqualTo("book");
        assertThat(RateLimitInterceptor.group(req("GET", "/api/rides"))).isEqualTo("default");
    }

    @Test
    void rejectsOverTheLimitWithRetryAfter() {
        given(redis.opsForValue()).willReturn(ops);
        given(ops.increment(anyString())).willReturn(1L, 2L, 3L);
        var request = req("POST", "/api/rides");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.retryAfterSeconds()).isEqualTo(40); // window ends at 10:46:00
                });
    }

    @Test
    void failsOpenWhenRedisIsDown() {
        given(redis.opsForValue()).willReturn(ops);
        given(ops.increment(anyString())).willThrow(new RedisConnectionFailureException("down"));

        assertThat(interceptor.preHandle(req("POST", "/api/rides"), new MockHttpServletResponse(), new Object()))
                .isTrue();
    }

    private static MockHttpServletRequest req(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }
}
