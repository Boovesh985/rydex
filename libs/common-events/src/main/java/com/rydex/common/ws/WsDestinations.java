package com.rydex.common.ws;

import java.util.UUID;

/**
 * STOMP destinations of tracking-service's WebSocket API (ADR 0011). User destinations are
 * written without the {@code /user} prefix, as Spring's user-destination support expects;
 * clients subscribe to {@code /user/queue/...}.
 */
public final class WsDestinations {

    /** Ride offers for a driver, and what happened to them. */
    public static final String OFFERS = "/queue/offers";
    /** Ride status notifications for riders and drivers. */
    public static final String NOTIFICATIONS = "/queue/notifications";
    /** Problems with something the client sent (e.g. location updates too frequent). */
    public static final String ERRORS = "/queue/errors";

    private static final String RIDE_TOPIC_PREFIX = "/topic/rides/";
    private static final String LOCATION_SUFFIX = "/location";

    private WsDestinations() {
    }

    /** Live position of the driver on a ride, for its rider. */
    public static String rideLocation(UUID rideId) {
        return RIDE_TOPIC_PREFIX + rideId + LOCATION_SUFFIX;
    }

    /** The ride id of a {@link #rideLocation} destination, or null if it is not one. */
    public static UUID rideIdOfLocationTopic(String destination) {
        if (destination == null || !destination.startsWith(RIDE_TOPIC_PREFIX) || !destination.endsWith(LOCATION_SUFFIX)) {
            return null;
        }
        String id = destination.substring(RIDE_TOPIC_PREFIX.length(), destination.length() - LOCATION_SUFFIX.length());
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
