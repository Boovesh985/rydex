package com.rydex.ride.geo;

import com.rydex.common.RedisKeys;
import com.rydex.common.geo.GeoPoint;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Routes via OSRM, cached in Redis for an hour. When OSRM is down (or the circuit is
 * open) it falls back to a straight-line estimate so fare quotes never fail.
 */
@Service
public class RoutingService {

    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

    private final OsrmClient osrm;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final GeoProperties props;
    private final CircuitBreaker breaker;

    RoutingService(OsrmClient osrm, StringRedisTemplate redis, JsonMapper json, GeoProperties props,
                   CircuitBreakerRegistry breakers) {
        this.osrm = osrm;
        this.redis = redis;
        this.json = json;
        this.props = props;
        this.breaker = breakers.circuitBreaker("osrm");
    }

    public RouteResult route(GeoPoint from, GeoPoint to) {
        String key = RedisKeys.routeCache(cacheKey(from, to));
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            return json.readValue(cached, RouteResult.class);
        }
        try {
            List<RouteResult.Alternative> routes = breaker.executeSupplier(() -> osrm.route(from, to));
            var best = routes.getFirst();
            var result = new RouteResult(best.distanceM(), best.durationS(), best.polyline(), RouteResult.Source.OSRM,
                    routes.subList(1, routes.size()));
            redis.opsForValue().set(key, json.writeValueAsString(result), props.routeCacheTtl());
            return result;
        } catch (RuntimeException e) {
            log.warn("OSRM unavailable, using straight-line estimate: {}",
                    NestedExceptionUtils.getMostSpecificCause(e).toString());
            return estimate(from, to);
        }
    }

    RouteResult estimate(GeoPoint from, GeoPoint to) {
        double meters = from.distanceMetersTo(to) * props.roadFactor();
        double seconds = meters / (props.fallbackSpeedKmh() * 1000 / 3600);
        return new RouteResult((int) Math.round(meters), (int) Math.round(seconds), null,
                RouteResult.Source.ESTIMATED, List.of());
    }

    /** ~11 m precision: nearby repeat lookups share a cache entry. */
    private static String cacheKey(GeoPoint from, GeoPoint to) {
        return String.format(Locale.ROOT, "%.4f,%.4f;%.4f,%.4f", from.lat(), from.lng(), to.lat(), to.lng());
    }
}
