package com.rydex.ride.ride;

import com.rydex.common.RedisKeys;
import com.rydex.common.geo.GeoPoint;
import com.rydex.ride.web.ApiException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * A driver can only say they have arrived when their last reported position (the presence hash
 * {@code driver:{id}:loc} that tracking-service and driver-service keep, ADR 0011) is near the
 * pickup. Otherwise the rider is told "your driver is here" while the car is streets away, and the
 * trip then starts from the wrong place. Redis being down does not block arrivals.
 */
@Component
public class ArrivalCheck {

    private static final Logger log = LoggerFactory.getLogger(ArrivalCheck.class);
    private static final double EARTH_RADIUS_M = 6_371_000;

    private final StringRedisTemplate redis;
    private final RideProperties props;

    ArrivalCheck(StringRedisTemplate redis, RideProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public void requireNear(UUID driverId, GeoPoint pickup) {
        Map<Object, Object> loc;
        try {
            loc = redis.opsForHash().entries(RedisKeys.driverLocation(driverId));
        } catch (DataAccessException e) {
            log.warn("Cannot read driver {}'s position, allowing the arrival: {}", driverId, e.getMessage());
            return;
        }
        if (loc.get("lat") == null || loc.get("lng") == null) {
            throw ApiException.conflict("We don't know where you are yet: share your location, then mark arrival at the pickup");
        }
        double away = distanceM(Double.parseDouble(loc.get("lat").toString()), Double.parseDouble(loc.get("lng").toString()),
                pickup.lat(), pickup.lng());
        if (away > props.arrivalRadiusM()) {
            throw ApiException.conflict(String.format(Locale.ROOT,
                    "You are %s from the pickup. Mark arrival when you get there (within %d m).",
                    away >= 1000 ? String.format(Locale.ROOT, "%.1f km", away / 1000) : Math.round(away) + " m",
                    props.arrivalRadiusM()));
        }
    }

    static double distanceM(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(h));
    }
}
