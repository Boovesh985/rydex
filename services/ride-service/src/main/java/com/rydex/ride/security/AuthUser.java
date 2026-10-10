package com.rydex.ride.security;

import com.rydex.ride.user.Role;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** The authenticated caller, read from a validated access token. */
public record AuthUser(UUID id, Role role) {

    public static AuthUser from(Jwt jwt) {
        return new AuthUser(UUID.fromString(jwt.getSubject()), Role.valueOf(jwt.getClaimAsString(TokenService.ROLE_CLAIM)));
    }

    public boolean is(Role r) {
        return role == r;
    }

    /** Actor label for audit rows, e.g. {@code RIDER:5f0c...}. */
    public String actor() {
        return role.name() + ":" + id;
    }
}
