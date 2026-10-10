package com.rydex.ride.user;

import com.rydex.ride.security.AuthUser;
import com.rydex.ride.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Users")
class MeController {

    private final UserRepository users;

    MeController(UserRepository users) {
        this.users = users;
    }

    @GetMapping("/api/me")
    @Operation(summary = "The signed-in user")
    UserView me(@AuthenticationPrincipal Jwt jwt) {
        return users.findById(AuthUser.from(jwt).id())
                .map(UserView::of)
                .orElseThrow(() -> ApiException.notFound("User"));
    }
}
