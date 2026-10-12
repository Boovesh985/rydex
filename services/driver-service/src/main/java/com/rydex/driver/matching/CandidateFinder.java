package com.rydex.driver.matching;

import com.rydex.common.RedisKeys;
import com.rydex.common.RideType;
import com.rydex.common.geo.GeoPoint;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoSearchCommandArgs;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Component;

/**
 * Finds the nearest available drivers of a ride type with {@code GEOSEARCH ... BYRADIUS ... ASC},
 * widening the radius (3 → 5 → 8 km by default) until someone is found. Redis answers a radius
 * query by scanning the geohash cell that covers the radius plus its 8 neighbours, which is the
 * 3x3 geohash search of master prompt §7.
 */
@Component
public class CandidateFinder {

    private final StringRedisTemplate redis;
    private final MatchingProperties props;

    public CandidateFinder(StringRedisTemplate redis, MatchingProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** Nearest first; drivers in {@code exclude} (already offered this ride) are skipped. */
    public List<Candidate> find(RideType type, GeoPoint pickup, Collection<UUID> exclude) {
        int limit = props.candidatesPerSearch();
        for (double radiusKm : props.searchRadiiKm()) {
            List<Candidate> found = search(type, pickup, radiusKm, limit + exclude.size()).stream()
                    .filter(c -> !exclude.contains(c.driverId()))
                    .limit(limit)
                    .toList();
            if (!found.isEmpty()) {
                return found;
            }
        }
        return List.of();
    }

    private List<Candidate> search(RideType type, GeoPoint p, double radiusKm, int count) {
        var results = redis.opsForGeo().search(
                RedisKeys.driversGeo(type),
                GeoReference.fromCoordinate(p.lng(), p.lat()),
                new Distance(radiusKm, Metrics.KILOMETERS),
                GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().includeCoordinates().sortAscending().limit(count));
        if (results == null) {
            return List.of();
        }
        return results.getContent().stream()
                .map(r -> new Candidate(
                        UUID.fromString(r.getContent().getName()),
                        GeoPoint.of(r.getContent().getPoint().getY(), r.getContent().getPoint().getX()),
                        (int) Math.round(r.getDistance().in(Metrics.KILOMETERS).getValue() * 1000)))
                .toList();
    }
}
