package com.rydex.ride.geo;

import com.rydex.common.RedisKeys;
import com.rydex.ride.web.ApiException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Geocoding through Nominatim with a 24 h Redis cache. Nominatim's usage policy allows
 * at most 1 request/second, so outbound calls take a cluster-wide Redis slot first.
 */
@Service
public class PlacesService {

    private static final Logger log = LoggerFactory.getLogger(PlacesService.class);
    private static final String THROTTLE_KEY = "throttle:nominatim";
    private static final Duration MAX_WAIT = Duration.ofSeconds(3);
    private static final TypeReference<List<Place>> PLACE_LIST = new TypeReference<>() { };

    private final NominatimClient nominatim;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final GeoProperties props;
    private final CircuitBreaker breaker;

    PlacesService(NominatimClient nominatim, StringRedisTemplate redis, JsonMapper json, GeoProperties props,
                  CircuitBreakerRegistry breakers) {
        this.nominatim = nominatim;
        this.redis = redis;
        this.json = json;
        this.props = props;
        this.breaker = breakers.circuitBreaker("nominatim");
    }

    public List<Place> search(String query, Double nearLat, Double nearLng) {
        String normalized = query.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        String bias = nearLat == null || nearLng == null ? "-" : String.format(Locale.ROOT, "%.1f,%.1f", nearLat, nearLng);
        String key = RedisKeys.geoCache("search:" + bias + ":" + normalized);
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            return json.readValue(cached, PLACE_LIST);
        }
        List<Place> result = callNominatim(() -> nominatim.search(normalized, nearLat, nearLng));
        redis.opsForValue().set(key, json.writeValueAsString(result), props.placesCacheTtl());
        return result;
    }

    public Place reverse(double lat, double lng) {
        String key = RedisKeys.geoCache(String.format(Locale.ROOT, "reverse:%.4f,%.4f", lat, lng));
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            return json.readValue(cached, Place.class);
        }
        Place place = callNominatim(() -> nominatim.reverse(lat, lng))
                .orElseThrow(() -> ApiException.notFound("Address for this location"));
        redis.opsForValue().set(key, json.writeValueAsString(place), props.placesCacheTtl());
        return place;
    }

    private <T> T callNominatim(Supplier<T> call) {
        acquireSlot();
        try {
            return breaker.executeSupplier(call);
        } catch (CallNotPermittedException e) {
            throw ApiException.unavailable("Place search is temporarily unavailable");
        } catch (RuntimeException e) {
            log.warn("Nominatim call failed: {}", NestedExceptionUtils.getMostSpecificCause(e).toString());
            throw ApiException.unavailable("Place search is temporarily unavailable");
        }
    }

    /** Waits for the shared 1-per-interval slot; gives up after a few seconds. */
    private void acquireSlot() {
        long deadline = System.nanoTime() + MAX_WAIT.toNanos();
        while (true) {
            Boolean acquired = redis.opsForValue().setIfAbsent(THROTTLE_KEY, "1", props.nominatimInterval());
            if (Boolean.TRUE.equals(acquired)) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw ApiException.tooManyRequests("Place search is busy, please retry", 1);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ApiException.unavailable("Interrupted while waiting for place search");
            }
        }
    }
}
