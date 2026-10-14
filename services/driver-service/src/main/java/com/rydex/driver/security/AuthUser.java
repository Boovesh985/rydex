package com.rydex.driver.security;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** The authenticated caller, read from a validated access token issued by ride-service. */
public record AuthUser(UUID id, String role, String name) {

    public static final String ROLE_CLAIM = "role";
    public static final String NAME_CLAIM = "name";

    public static final String DRIVER = "DRIVER";
    public static final String ADMIN = "ADMIN";

    public static AuthUser from(Jwt jwt) {
        return new AuthUser(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString(ROLE_CLAIM),
                jwt.getClaimAsString(NAME_CLAIM));
    }

    public boolean isDriver() {
        return DRIVER.equals(role);
    }

    public boolean isAdmin() {
        return ADMIN.equals(role);
    }
}
