package com.rydex.tracking.ws;

import com.rydex.common.ws.WsDestinations;
import com.rydex.tracking.ride.RideAccess;
import com.rydex.tracking.security.AuthUser;
import java.security.Principal;
import java.time.Clock;
import java.util.UUID;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Authentication and authorisation for every inbound STOMP frame (master prompt §10, ADR 0011).
 * <ul>
 *   <li>CONNECT must carry {@code Authorization: Bearer <access token>}; the session's user is the
 *       token's subject.</li>
 *   <li>Once the token expires the session can no longer subscribe or send: the client reconnects
 *       with a fresh token. Access tokens last 15 minutes, so a revoked user cannot keep a socket
 *       open indefinitely.</li>
 *   <li>SUBSCRIBE: drivers to {@code /user/queue/offers}; anyone to their own notifications and
 *       errors; a ride's location topic only for its rider, its driver or an admin.</li>
 *   <li>SEND: only drivers, only to {@code /app/driver.location} and {@code /app/offer.respond}.</li>
 * </ul>
 * A refused frame gets a STOMP ERROR frame, which closes the connection.
 */
@Component
class StompAuthInterceptor implements ChannelInterceptor {

    static final String USER_OFFERS = "/user" + WsDestinations.OFFERS;
    static final String USER_NOTIFICATIONS = "/user" + WsDestinations.NOTIFICATIONS;
    static final String USER_ERRORS = "/user" + WsDestinations.ERRORS;
    static final String SEND_LOCATION = "/app/driver.location";
    static final String SEND_OFFER_RESPONSE = "/app/offer.respond";

    private final JwtDecoder jwtDecoder;
    private final RideAccess access;
    private final Clock clock;

    StompAuthInterceptor(JwtDecoder jwtDecoder, RideAccess access, Clock clock) {
        this.jwtDecoder = jwtDecoder;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor stomp = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (stomp == null || stomp.getCommand() == null) {
            return message;
        }
        switch (stomp.getCommand()) {
            case CONNECT, STOMP -> stomp.setUser(authenticate(stomp.getFirstNativeHeader("Authorization")));
            case SUBSCRIBE -> authorizeSubscribe(user(stomp), stomp.getDestination());
            case SEND -> authorizeSend(user(stomp), stomp.getDestination());
            default -> {
                // UNSUBSCRIBE, DISCONNECT, ACK/NACK need no checks
            }
        }
        return message;
    }

    private AuthUser authenticate(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new BadCredentialsException("CONNECT needs an 'Authorization: Bearer <access token>' header");
        }
        try {
            return AuthUser.from(jwtDecoder.decode(authorization.substring(7).trim()));
        } catch (JwtException e) {
            throw new BadCredentialsException("Invalid access token");
        }
    }

    private AuthUser user(StompHeaderAccessor stomp) {
        Principal principal = stomp.getUser();
        if (!(principal instanceof AuthUser user)) {
            throw new AccessDeniedException("Not connected");
        }
        if (user.isExpiredAt(clock.instant())) {
            throw new CredentialsExpiredException("Access token expired; reconnect with a fresh token");
        }
        return user;
    }

    private void authorizeSubscribe(AuthUser user, String destination) {
        if (USER_NOTIFICATIONS.equals(destination) || USER_ERRORS.equals(destination)) {
            return;
        }
        if (USER_OFFERS.equals(destination)) {
            requireDriver(user, destination);
            return;
        }
        UUID rideId = WsDestinations.rideIdOfLocationTopic(destination);
        if (rideId != null && access.canWatch(user, rideId)) {
            return;
        }
        throw new AccessDeniedException("You cannot subscribe to " + destination);
    }

    private static void authorizeSend(AuthUser user, String destination) {
        if (SEND_LOCATION.equals(destination) || SEND_OFFER_RESPONSE.equals(destination)) {
            requireDriver(user, destination);
            return;
        }
        throw new AccessDeniedException("Unknown destination " + destination);
    }

    private static void requireDriver(AuthUser user, String destination) {
        if (!user.isDriver()) {
            throw new AccessDeniedException("Only drivers can use " + destination);
        }
    }
}
