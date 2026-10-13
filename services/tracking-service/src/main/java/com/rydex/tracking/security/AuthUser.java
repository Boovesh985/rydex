package com.rydex.tracking.security;

import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The authenticated caller, from a validated access token issued by ride-service: on REST
 * requests, and as the STOMP session's {@link java.security.Principal} (its name is the user id,
 * which is what user destinations such as {@code /user/queue/offers} are addressed to).
 *
 * @param token     the raw access token, forwarded when acting for the user on another service
 * @param expiresAt when the token expires; a STOMP session must reconnect with a fresh one after it
 */
public record AuthUser(UUID id, String role, String token, Instant expiresAt) implements java.security.Principal {

    public static final String ROLE_CLAIM = "role";

    public static AuthUser from(Jwt jwt) {
        return new AuthUser(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString(ROLE_CLAIM), jwt.getTokenValue(),
                jwt.getExpiresAt());
    }

    @Override
    public String getName() {
        return id.toString();
    }

    public boolean isDriver() {
        return "DRIVER".equals(role);
    }

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /** Never print the token. */
    @Override
    public String toString() {
        return "AuthUser[" + role + ":" + id + "]";
    }
}
