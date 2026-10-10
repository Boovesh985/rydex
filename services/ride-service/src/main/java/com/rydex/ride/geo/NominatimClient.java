package com.rydex.ride.geo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;

/** Thin client for the Nominatim search / reverse API. Callers must enforce the rate limit. */
public class NominatimClient {

    private static final double BIAS_BOX_DEGREES = 0.5;

    private final RestClient http;
    private final String countryCodes;

    public NominatimClient(RestClient http, String countryCodes) {
        this.http = http;
        this.countryCodes = countryCodes;
    }

    /** Free-text search, optionally biased (not restricted) towards a point. */
    public List<Place> search(String query, Double nearLat, Double nearLng) {
        JsonNode body = http.get()
                .uri(b -> {
                    UriBuilder u = b.path("/search")
                            .queryParam("q", query)
                            .queryParam("format", "jsonv2")
                            .queryParam("limit", 6);
                    if (countryCodes != null && !countryCodes.isBlank()) {
                        u.queryParam("countrycodes", countryCodes);
                    }
                    if (nearLat != null && nearLng != null) {
                        u.queryParam("viewbox", String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f",
                                nearLng - BIAS_BOX_DEGREES, nearLat + BIAS_BOX_DEGREES,
                                nearLng + BIAS_BOX_DEGREES, nearLat - BIAS_BOX_DEGREES));
                    }
                    return u.build();
                })
                .retrieve()
                .body(JsonNode.class);
        List<Place> places = new ArrayList<>();
        if (body != null) {
            for (JsonNode n : body) {
                places.add(toPlace(n));
            }
        }
        return places;
    }

    public Optional<Place> reverse(double lat, double lng) {
        JsonNode body = http.get()
                .uri(b -> b.path("/reverse")
                        .queryParam("lat", lat)
                        .queryParam("lon", lng)
                        .queryParam("format", "jsonv2")
                        .queryParam("zoom", 18)
                        .build())
                .retrieve()
                .body(JsonNode.class);
        if (body == null || body.has("error")) {
            return Optional.empty();
        }
        return Optional.of(toPlace(body));
    }

    private static Place toPlace(JsonNode n) {
        String address = n.path("display_name").asString();
        String name = n.path("name").asString();
        if (name == null || name.isBlank()) {
            int comma = address.indexOf(',');
            name = comma > 0 ? address.substring(0, comma) : address;
        }
        return new Place(name, address, n.path("lat").asDouble(), n.path("lon").asDouble());
    }
}
