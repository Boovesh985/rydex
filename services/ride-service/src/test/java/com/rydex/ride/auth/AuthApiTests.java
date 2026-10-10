package com.rydex.ride.auth;

import static com.rydex.ride.TestApi.token;
import static org.assertj.core.api.Assertions.assertThat;

import com.rydex.ride.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthApiTests extends IntegrationTest {

    @Test
    void registerThenReadProfile() {
        var auth = api().register("RIDER");

        assertThat(auth.path("tokenType").asString()).isEqualTo("Bearer");
        assertThat(auth.path("expiresIn").asLong()).isEqualTo(900);
        var me = api().get("/api/me", token(auth));
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(me.getBody().path("role").asString()).isEqualTo("RIDER");
        assertThat(me.getBody().has("passwordHash")).isFalse();
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() {
        var auth = api().register("RIDER");
        String email = auth.path("user").path("email").asString().toUpperCase();

        var res = api().post("/api/auth/register", null, Map.of(
                "name", "Dup", "email", email, "password", "Sup3rSecret!", "role", "RIDER"));

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getHeaders().getContentType().toString()).contains("application/problem+json");
        assertThat(res.getBody().path("title").asString()).isEqualTo("Conflict");
    }

    @Test
    void adminCannotSelfRegister() {
        var res = api().post("/api/auth/register", null, Map.of(
                "name", "Mallory", "email", "mallory@test.rydex.dev", "password", "Sup3rSecret!", "role", "ADMIN"));

        assertThat(res.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void invalidRegistrationReturnsValidationProblem() {
        var res = api().post("/api/auth/register", null, Map.of(
                "name", "", "email", "not-an-email", "password", "short", "role", "RIDER"));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getHeaders().getContentType().toString()).contains("application/problem+json");
    }

    @Test
    void loginChecksPassword() {
        var auth = api().register("DRIVER");
        String email = auth.path("user").path("email").asString();

        var bad = api().post("/api/auth/login", null, Map.of("email", email, "password", "wrong-password"));
        var good = api().post("/api/auth/login", null, Map.of("email", email, "password", "Sup3rSecret!"));
        var unknown = api().post("/api/auth/login", null, Map.of("email", "nobody@test.rydex.dev", "password", "x"));

        assertThat(bad.getStatusCode().value()).isEqualTo(401);
        assertThat(unknown.getStatusCode().value()).isEqualTo(401);
        assertThat(good.getStatusCode().value()).isEqualTo(200);
        assertThat(good.getBody().path("user").path("role").asString()).isEqualTo("DRIVER");
    }

    @Test
    void seededDemoAccountsCanSignIn() {
        var res = api().post("/api/auth/login", null, Map.of("email", "admin@rydex.dev", "password", "Rydex@123"));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().path("user").path("role").asString()).isEqualTo("ADMIN");
    }

    @Test
    void refreshRotatesAndDetectsReuse() {
        var auth = api().register("RIDER");
        String first = auth.path("refreshToken").asString();

        var rotated = api().post("/api/auth/refresh", null, Map.of("refreshToken", first));
        assertThat(rotated.getStatusCode().value()).isEqualTo(200);
        String second = rotated.getBody().path("refreshToken").asString();
        assertThat(second).isNotEqualTo(first);

        // replaying the already-used token revokes the whole family
        var reuse = api().post("/api/auth/refresh", null, Map.of("refreshToken", first));
        assertThat(reuse.getStatusCode().value()).isEqualTo(401);
        var afterReuse = api().post("/api/auth/refresh", null, Map.of("refreshToken", second));
        assertThat(afterReuse.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void logoutRevokesRefreshToken() {
        var auth = api().register("RIDER");
        String refresh = auth.path("refreshToken").asString();

        assertThat(api().post("/api/auth/logout", null, Map.of("refreshToken", refresh)).getStatusCode().value())
                .isEqualTo(204);
        assertThat(api().post("/api/auth/refresh", null, Map.of("refreshToken", refresh)).getStatusCode().value())
                .isEqualTo(401);
    }

    @Test
    void protectedEndpointsNeedAValidToken() {
        assertThat(api().get("/api/me", null).getStatusCode().value()).isEqualTo(401);
        assertThat(api().get("/api/me", "not.a.jwt").getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void jwksExposesTheSigningKey() {
        var res = api().get("/.well-known/jwks.json", null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        var key = res.getBody().path("keys").get(0);
        assertThat(key.path("kty").asString()).isEqualTo("RSA");
        assertThat(key.has("d")).as("private exponent must never be published").isFalse();
    }
}
