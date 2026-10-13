package com.rydex.payment.security;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** The authenticated caller, from a validated access token issued by ride-service. */
public record AuthUser(UUID id, String role) {

    public static final String ROLE_CLAIM = "role";

    public static AuthUser from(Jwt jwt) {
        return new AuthUser(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString(ROLE_CLAIM));
    }

    public boolean isRider() {
        return "RIDER".equals(role);
    }

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
