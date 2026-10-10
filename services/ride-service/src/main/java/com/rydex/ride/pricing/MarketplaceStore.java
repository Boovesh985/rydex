package com.rydex.ride.pricing;

import com.rydex.common.RedisKeys;
import com.rydex.common.RideType;
import com.rydex.common.geo.GeoPoint;
import com.rydex.common.geo.Geohash;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoSearchCommandArgs;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Component;

/**
 * Read/write access to the hot marketplace data in Redis: ride demand counters per
 * geohash cell, available-driver sets per cell (written by driver-service) and the
 * per-ride-type driver GEO sets.
 */
@Component
public class MarketplaceStore {

    private static final Duration DEMAND_TTL = Duration.ofHours(2);

    private final StringRedisTemplate redis;

    public MarketplaceStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void recordDemand(GeoPoint pickup, Instant at) {
        String key = RedisKeys.demand(Geohash.encode(pickup, Geohash.MATCHING_PRECISION), at);
        redis.opsForValue().increment(key);
        redis.expire(key, DEMAND_TTL);
    }

    /** Ride requests in the 3x3 cells around {@code p} over the current and previous 15-minute bucket. */
    public long demandAround(GeoPoint p, Instant at) {
        long bucket = RedisKeys.demandBucket(at);
        List<String> keys = Geohash.grid3x3(Geohash.encode(p, Geohash.MATCHING_PRECISION)).stream()
                .flatMap(cell -> java.util.stream.Stream.of(RedisKeys.demand(cell, bucket), RedisKeys.demand(cell, bucket - 1)))
                .toList();
        List<String> values = redis.opsForValue().multiGet(keys);
        return values == null ? 0 : values.stream().filter(v -> v != null).mapToLong(Long::parseLong).sum();
    }

    /** Available drivers (any type) in the 3x3 cells around {@code p}. */
    public long supplyAround(GeoPoint p) {
        long total = 0;
        for (String cell : Geohash.grid3x3(Geohash.encode(p, Geohash.MATCHING_PRECISION))) {
            Long n = redis.opsForSet().size(RedisKeys.supply(cell));
            total += n == null ? 0 : n;
        }
        return total;
    }

    /** Nearest available drivers of a type, closest first. */
    public List<NearbyDriver> nearbyDrivers(RideType type, GeoPoint p, double radiusKm, int limit) {
        var results = redis.opsForGeo().search(
                RedisKeys.driversGeo(type),
                GeoReference.fromCoordinate(p.lng(), p.lat()),
                new Distance(radiusKm, Metrics.KILOMETERS),
                GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().sortAscending().limit(limit));
        if (results == null) {
            return List.of();
        }
        return results.getContent().stream()
                .map(r -> new NearbyDriver(UUID.fromString(r.getContent().getName()),
                        (int) Math.round(r.getDistance().in(Metrics.KILOMETERS).getValue() * 1000)))
                .toList();
    }

    public record NearbyDriver(UUID driverId, int distanceM) {
    }
}
