package com.rydex.tracking.location;

import com.rydex.common.geo.GeoPoint;
import java.util.Locale;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Client for the OSRM {@code route} service: driving time and distance between two points. */
public class OsrmRouteClient {

    public record Route(int seconds, int meters) {
    }

    private final RestClient http;

    public OsrmRouteClient(RestClient http) {
        this.http = http;
    }

    /** Throws when OSRM fails or finds no route; callers fall back to an estimate. */
    public Route route(GeoPoint from, GeoPoint to) {
        String coords = String.format(Locale.ROOT, "%.6f,%.6f;%.6f,%.6f", from.lng(), from.lat(), to.lng(), to.lat());
        JsonNode body = http.get()
                // literal path: OSRM needs ',' and ';' unescaped
                .uri("/route/v1/driving/" + coords + "?overview=false&alternatives=false&steps=false")
                .retrieve()
                .body(JsonNode.class);
        if (body == null || !"Ok".equals(body.path("code").asString()) || body.path("routes").isEmpty()) {
            throw new IllegalStateException("OSRM route returned " + (body == null ? "no body" : body.path("code").asString()));
        }
        JsonNode route = body.path("routes").get(0);
        return new Route((int) Math.round(route.path("duration").asDouble()),
                (int) Math.round(route.path("distance").asDouble()));
    }
}
