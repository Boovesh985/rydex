package com.rydex.tracking.location;

import com.rydex.common.geo.GeoPoint;
import com.rydex.tracking.config.RoutingProperties;
import com.rydex.tracking.config.TrackingProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Live ETA of a driver to the pickup or the drop.
 *
 * <p>Asking OSRM on every 3 s update would hammer the public demo server, so a route's duration
 * is stored for {@code etaRefresh} (20 s) in {@code tracking:eta:{rideId}:{phase}} and counted down
 * in between: a driver moving as expected sees a smooth countdown, and the next OSRM answer
 * corrects any drift.
 *
 * <p>OSRM is never called on the broadcast path: the public server can take seconds to answer,
 * and the rider should see the car move at once. When there is no stored ETA, the update carries
 * the straight-line estimate (distance × road factor at the average city speed, as elsewhere in
 * Rydex; {@code estimated = true}) and one background call per ride and phase fetches the OSRM
 * route for the following updates. When OSRM is down (or its circuit is open) the estimate is what
 * gets stored.
 */
@Service
public class EtaService {

    private static final Logger log = LoggerFactory.getLogger(EtaService.class);

    /** Seconds and metres left, and whether they are a straight-line estimate. */
    public record Eta(int seconds, int meters, boolean estimated) {
    }

    private final OsrmRouteClient osrm;
    private final CircuitBreaker breaker;
    private final StringRedisTemplate redis;
    private final RoutingProperties routing;
    private final Duration refresh;
    private final Clock clock;
    private final ExecutorService refresher = Executors.newVirtualThreadPerTaskExecutor();

    EtaService(OsrmRouteClient osrm, CircuitBreakerRegistry breakers, StringRedisTemplate redis,
               RoutingProperties routing, TrackingProperties tracking, Clock clock) {
        this.osrm = osrm;
        this.breaker = breakers.circuitBreaker("osrm-route");
        this.redis = redis;
        this.routing = routing;
        this.refresh = tracking.etaRefresh();
        this.clock = clock;
    }

    public Eta eta(UUID rideId, LiveLocation.Phase phase, GeoPoint from, GeoPoint to) {
        if (phase == LiveLocation.Phase.AT_PICKUP) {
            return new Eta(0, (int) Math.round(from.distanceMetersTo(to)), false);
        }
        Instant now = clock.instant();
        String key = "tracking:eta:" + rideId + ":" + phase;
        Eta cached = countDown(redis.<String, String>opsForHash().entries(key), now);
        if (cached != null) {
            return cached;
        }
        refreshInBackground(key, from, to);
        return estimate(from, to);
    }

    /** Asks OSRM for the route and stores it; at most one call in flight per ride and phase. */
    private void refreshInBackground(String key, GeoPoint from, GeoPoint to) {
        String inFlight = key + ":refreshing";
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(inFlight, "1", Duration.ofSeconds(10)))) {
            return;
        }
        Instant asked = clock.instant();
        refresher.execute(() -> {
            try {
                Eta fresh = route(from, to);
                redis.opsForHash().putAll(key, Map.of(
                        "seconds", Integer.toString(fresh.seconds()),
                        "meters", Integer.toString(fresh.meters()),
                        "estimated", Boolean.toString(fresh.estimated()),
                        // counted down from when the driver was where this route starts
                        "at", Long.toString(asked.toEpochMilli())));
                redis.expire(key, refresh);
            } catch (RuntimeException e) {
                log.debug("Could not store an ETA: {}", e.toString());
            } finally {
                redis.delete(inFlight);
            }
        });
    }

    @PreDestroy
    void stop() {
        refresher.shutdownNow();
    }

    /** The stored ETA minus the time since it was computed (distance scaled alike), or null if none. */
    private static Eta countDown(Map<String, String> stored, Instant now) {
        if (stored == null || stored.get("seconds") == null || stored.get("at") == null) {
            return null;
        }
        int seconds = Integer.parseInt(stored.get("seconds"));
        int meters = Integer.parseInt(stored.get("meters"));
        long elapsed = Math.max(0, Duration.between(Instant.ofEpochMilli(Long.parseLong(stored.get("at"))), now).toSeconds());
        int left = (int) Math.max(0, seconds - elapsed);
        int metersLeft = seconds == 0 ? 0 : (int) Math.round((double) meters * left / seconds);
        return new Eta(left, metersLeft, Boolean.parseBoolean(stored.get("estimated")));
    }

    private Eta route(GeoPoint from, GeoPoint to) {
        try {
            return breaker.executeSupplier(() -> {
                OsrmRouteClient.Route r = osrm.route(from, to);
                return new Eta(r.seconds(), r.meters(), false);
            });
        } catch (RuntimeException e) {
            log.debug("OSRM route unavailable, using a straight-line ETA: {}", e.toString());
            return estimate(from, to);
        }
    }

    /** Straight-line distance × road factor at the average city speed. */
    Eta estimate(GeoPoint from, GeoPoint to) {
        double meters = from.distanceMetersTo(to) * routing.roadFactor();
        int seconds = (int) Math.ceil(meters / (routing.fallbackSpeedKmh() / 3.6));
        return new Eta(seconds, (int) Math.round(meters), true);
    }
}
