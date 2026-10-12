package com.rydex.driver;

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
        return call(HttpMethod.GET, path, token, null);
    }

    public ResponseEntity<JsonNode> post(String path, String token, Object body) {
        return call(HttpMethod.POST, path, token, body);
    }

    public ResponseEntity<JsonNode> put(String path, String token, Object body) {
        return call(HttpMethod.PUT, path, token, body);
    }

    public ResponseEntity<JsonNode> patch(String path, String token, Object body) {
        return call(HttpMethod.PATCH, path, token, body);
    }

    public ResponseEntity<JsonNode> call(HttpMethod method, String path, String token, Object body) {
        var spec = http.method(method).uri(path);
        if (token != null) {
            spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.retrieve().toEntity(JsonNode.class);
    }
}
