package com.rydex.ride.geo;

import com.rydex.common.geo.GeoPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Thin client for the OSRM HTTP API (public demo server by default). */
public class OsrmClient {

    private final RestClient http;

    public OsrmClient(RestClient http) {
        this.http = http;
    }

    /** Fastest route first, then up to two alternatives. */
    public List<RouteResult.Alternative> route(GeoPoint from, GeoPoint to) {
        String coords = String.format(Locale.ROOT, "%.6f,%.6f;%.6f,%.6f", from.lng(), from.lat(), to.lng(), to.lat());
        JsonNode body = http.get()
                // literal path: OSRM needs ',' and ';' unescaped (both are legal in a URI path)
                .uri("/route/v1/driving/" + coords + "?overview=full&geometries=polyline6&alternatives=true&steps=false")
                .retrieve()
                .body(JsonNode.class);
        if (body == null || !"Ok".equals(body.path("code").asString())) {
            throw new RoutingException("OSRM returned " + (body == null ? "no body" : body.path("code").asString()));
        }
        List<RouteResult.Alternative> routes = new ArrayList<>();
        for (JsonNode r : body.path("routes")) {
            routes.add(new RouteResult.Alternative(
                    (int) Math.round(r.path("distance").asDouble()),
                    (int) Math.round(r.path("duration").asDouble()),
                    r.path("geometry").asString()));
        }
        if (routes.isEmpty()) {
            throw new RoutingException("OSRM found no route");
        }
        return routes;
    }

    public static class RoutingException extends RuntimeException {
        public RoutingException(String message) {
            super(message);
        }
    }
}
