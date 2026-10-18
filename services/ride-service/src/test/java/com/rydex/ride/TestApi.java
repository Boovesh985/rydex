package com.rydex.ride;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Small HTTP helper for integration tests: never throws on 4xx/5xx, always returns JSON. */
public class TestApi {

    private final RestClient http;

    TestApi(int port) {
        this.http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (req, res) -> { })
                .build();
    }

    public ResponseEntity<JsonNode> get(String path, String token) {
        return call(HttpMethod.GET, path, token, null, Map.of());
    }

    public ResponseEntity<JsonNode> post(String path, String token, Object body) {
        return call(HttpMethod.POST, path, token, body, Map.of());
    }

    public ResponseEntity<JsonNode> put(String path, String token, Object body) {
        return call(HttpMethod.PUT, path, token, body, Map.of());
    }

    public ResponseEntity<JsonNode> delete(String path, String token) {
        return call(HttpMethod.DELETE, path, token, null, Map.of());
    }

    public ResponseEntity<JsonNode> call(HttpMethod method, String path, String token, Object body,
                                         Map<String, String> headers) {
        var spec = http.method(method).uri(path);
        if (token != null) {
            spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        headers.forEach(spec::header);
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.retrieve().toEntity(JsonNode.class);
    }

    /** Registers a fresh user and returns the auth response body. */
    public JsonNode register(String role) {
        String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@test.rydex.dev";
        var res = post("/api/auth/register", null, Map.of(
                "name", "Test " + role, "email", email, "phone", "+919876543210",
                "password", "Sup3rSecret!", "role", role));
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("register failed: " + res.getBody());
        }
        return res.getBody();
    }

    public static String token(JsonNode auth) {
        return auth.path("accessToken").asString();
    }

    public static UUID userId(JsonNode auth) {
        return UUID.fromString(auth.path("user").path("id").asString());
    }
}
