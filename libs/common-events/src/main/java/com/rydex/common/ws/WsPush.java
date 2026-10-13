package com.rydex.common.ws;

import java.util.Objects;
import java.util.UUID;

/**
 * A message for WebSocket clients, published as JSON on the Redis channel {@link #CHANNEL}.
 * Every tracking-service instance subscribes and delivers it to the sessions it holds, so a
 * push reaches the client whichever instance it is connected to (ADR 0011). Other services
 * (e.g. driver-service for ride offers) push the same way.
 *
 * @param user        target user id for a user destination, or null for a broadcast topic
 * @param destination e.g. {@link WsDestinations#OFFERS} (user) or {@link WsDestinations#rideLocation} (topic)
 * @param payload     the message body, serialised as JSON
 */
public record WsPush(String user, String destination, Object payload) {

    public static final String CHANNEL = "ws:push";

    public WsPush {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(payload, "payload");
    }

    public static WsPush toUser(UUID userId, String destination, Object payload) {
        return new WsPush(userId.toString(), destination, payload);
    }

    public static WsPush toTopic(String destination, Object payload) {
        return new WsPush(null, destination, payload);
    }
}
