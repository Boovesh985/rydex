package com.rydex.common;

import java.time.Instant;
import java.util.UUID;

/**
 * Redis key layout shared by every service (master prompt §6). Services that write a
 * key and services that read it must agree, so the names live in one place.
 */
public final class RedisKeys {

    /** Length of a demand bucket. */
    public static final long DEMAND_BUCKET_SECONDS = 15 * 60;

    private RedisKeys() {
    }

    /** GEO set of available drivers of one ride type. */
    public static String driversGeo(RideType type) {
        return "geo:drivers:" + type.name();
    }

    /** ZSET of driver heartbeats, score = epoch millis. */
    public static String driverLastSeen() {
        return "driver:lastseen";
    }

    public static String driverLocation(UUID driverId) {
        return "driver:" + driverId + ":loc";
    }

    public static String driverLock(UUID driverId) {
        return "driver:" + driverId + ":lock";
    }

    public static String rideOffer(UUID rideId) {
        return "ride:" + rideId + ":offer";
    }

    public static String otpAttempts(UUID rideId) {
        return "ride:" + rideId + ":otp_attempts";
    }

    /** Ride requests counted per geohash-6 cell per 15-minute bucket. */
    public static String demand(String geohash6, Instant at) {
        return demand(geohash6, demandBucket(at));
    }

    public static String demand(String geohash6, long bucket) {
        return "demand:" + geohash6 + ":" + bucket;
    }

    public static long demandBucket(Instant at) {
        return at.getEpochSecond() / DEMAND_BUCKET_SECONDS;
    }

    /** SET of available driver ids per geohash-6 cell. */
    public static String supply(String geohash6) {
        return "supply:" + geohash6;
    }

    public static String routeCache(String hash) {
        return "cache:route:" + hash;
    }

    public static String geoCache(String query) {
        return "cache:geo:" + query;
    }

    public static String rateLimit(String subject, String route, long window) {
        return "ratelimit:" + subject + ":" + route + ":" + window;
    }

    public static String idempotency(UUID userId, String key) {
        return "idem:" + userId + ":" + key;
    }
}
