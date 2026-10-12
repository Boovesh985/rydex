package com.rydex.driver.presence;

import com.rydex.common.RedisKeys;
import com.rydex.common.RideType;
import com.rydex.common.geo.Geohash;
import com.rydex.driver.driver.DriverStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Driver presence in Redis (master prompt §6): the availability projection
 * {@code driver:{id}:state}, the last location {@code driver:{id}:loc} (TTL), heartbeats in
 * {@code driver:lastseen}, and the search indexes ({@code geo:drivers:{type}} and
 * {@code supply:{cell}}). Postgres stays the source of truth for status; every write that
 * touches several keys runs as one Lua script.
 */
@Component
public class PresenceStore {

    private static final List<String> ALL_GEO_KEYS = Arrays.stream(RideType.values()).map(RedisKeys::driversGeo).toList();

    private final StringRedisTemplate redis;
    private final PresenceProperties props;
    private final DefaultRedisScript<Long> locationScript = script("redis/location.lua");
    private final DefaultRedisScript<Long> availabilityScript = script("redis/availability.lua");
    private final DefaultRedisScript<Long> evictScript = script("redis/evict.lua");

    public PresenceStore(StringRedisTemplate redis, PresenceProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public enum HeartbeatResult {
        /** Driver is AVAILABLE and now searchable at the new position. */
        INDEXED,
        /** Location stored, but the driver is offline or on a trip. */
        NOT_AVAILABLE,
        /** Redis has no availability projection for this driver (e.g. after a flush); resync from Postgres. */
        STATE_MISSING
    }

    public HeartbeatResult heartbeat(UUID driverId, double lat, double lng, Double heading, Double speed, Instant at) {
        // location TTL = staleness window: a silent driver's position disappears with them
        Long result = redis.execute(locationScript,
                List.of(RedisKeys.driverState(driverId), RedisKeys.driverLocation(driverId), RedisKeys.driverLastSeen()),
                driverId.toString(), Double.toString(lat), Double.toString(lng),
                heading == null ? "" : heading.toString(), speed == null ? "" : speed.toString(),
                Geohash.encode(lat, lng, Geohash.MATCHING_PRECISION), Long.toString(at.toEpochMilli()),
                Long.toString(props.staleAfter().toMillis()),
                RedisKeys.DRIVERS_GEO_PREFIX, RedisKeys.SUPPLY_PREFIX);
        if (result == null || result < 0) {
            return HeartbeatResult.STATE_MISSING;
        }
        return result == 1 ? HeartbeatResult.INDEXED : HeartbeatResult.NOT_AVAILABLE;
    }

    /** Writes the availability projection; returns true if the driver is now searchable. */
    public boolean setStatus(UUID driverId, DriverStatus status, RideType type) {
        List<String> keys = Stream.concat(
                Stream.of(RedisKeys.driverState(driverId), RedisKeys.driverLocation(driverId)),
                ALL_GEO_KEYS.stream()).toList();
        Long result = redis.execute(availabilityScript, keys,
                driverId.toString(), status.name(), type.name(), RedisKeys.DRIVERS_GEO_PREFIX, RedisKeys.SUPPLY_PREFIX);
        return result != null && result == 1;
    }

    public Optional<DriverStatus> status(UUID driverId) {
        Object status = redis.opsForHash().get(RedisKeys.driverState(driverId), "status");
        return Optional.ofNullable(status).map(s -> DriverStatus.valueOf(s.toString()));
    }

    /** Status and ride type from the availability projection. */
    public Optional<State> state(UUID driverId) {
        List<Object> v = redis.opsForHash().multiGet(RedisKeys.driverState(driverId), List.of("status", "type"));
        if (v == null || v.get(0) == null || v.get(1) == null) {
            return Optional.empty();
        }
        return Optional.of(new State(DriverStatus.valueOf(v.get(0).toString()), RideType.valueOf(v.get(1).toString())));
    }

    public record State(DriverStatus status, RideType type) {
    }

    public boolean isAvailable(UUID driverId) {
        return status(driverId).filter(s -> s == DriverStatus.AVAILABLE).isPresent();
    }

    public Optional<DriverLocation> location(UUID driverId) {
        Map<Object, Object> h = redis.opsForHash().entries(RedisKeys.driverLocation(driverId));
        return h.isEmpty() ? Optional.empty() : Optional.of(toLocation(driverId, h));
    }

    /** Drivers that sent a heartbeat since {@code since}, newest last. */
    public List<UUID> seenSince(Instant since, int limit) {
        Set<String> ids = redis.opsForZSet().rangeByScore(RedisKeys.driverLastSeen(),
                since.toEpochMilli(), Double.POSITIVE_INFINITY, 0, limit);
        return ids == null ? List.of() : ids.stream().map(UUID::fromString).toList();
    }

    /** Drivers whose last heartbeat is older than {@code cutoff}. */
    public List<UUID> silentSince(Instant cutoff, int limit) {
        Set<String> ids = redis.opsForZSet().rangeByScore(RedisKeys.driverLastSeen(),
                Double.NEGATIVE_INFINITY, cutoff.toEpochMilli(), 0, limit);
        return ids == null ? List.of() : ids.stream().map(UUID::fromString).toList();
    }

    /** Removes a silent driver from the search indexes; a heartbeat after {@code cutoff} wins. */
    public boolean evict(UUID driverId, Instant cutoff) {
        List<String> keys = Stream.concat(
                Stream.of(RedisKeys.driverState(driverId), RedisKeys.driverLastSeen()),
                ALL_GEO_KEYS.stream()).toList();
        Long result = redis.execute(evictScript, keys,
                driverId.toString(), Long.toString(cutoff.toEpochMilli()), RedisKeys.SUPPLY_PREFIX);
        return result != null && result == 1;
    }

    /**
     * Claims the right to store one location update. Updates arriving faster than
     * {@code minLocationInterval} are refused, which caps each driver at a few writes per second.
     */
    public boolean tryClaimLocationSlot(UUID driverId) {
        Duration interval = props.minLocationInterval();
        Boolean ok = redis.opsForValue().setIfAbsent(RedisKeys.driverLocationThrottle(driverId), "1", interval);
        return Boolean.TRUE.equals(ok);
    }

    /** Available drivers per geohash cell. */
    public long supply(String cell) {
        Long n = redis.opsForSet().size(RedisKeys.supply(cell));
        return n == null ? 0 : n;
    }

    public List<DriverLocation> locations(Collection<UUID> driverIds) {
        List<DriverLocation> result = new ArrayList<>(driverIds.size());
        for (UUID id : driverIds) {
            location(id).ifPresent(result::add);
        }
        return result;
    }

    private static DriverLocation toLocation(UUID driverId, Map<Object, Object> h) {
        return new DriverLocation(driverId,
                Double.parseDouble((String) h.get("lat")),
                Double.parseDouble((String) h.get("lng")),
                optionalDouble(h.get("heading")),
                optionalDouble(h.get("speed")),
                (String) h.get("geohash"),
                Instant.ofEpochMilli(Long.parseLong((String) h.get("ts"))));
    }

    private static Double optionalDouble(Object v) {
        return v == null || v.toString().isEmpty() ? null : Double.valueOf(v.toString());
    }

    private static DefaultRedisScript<Long> script(String path) {
        var script = new DefaultRedisScript<Long>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
}
