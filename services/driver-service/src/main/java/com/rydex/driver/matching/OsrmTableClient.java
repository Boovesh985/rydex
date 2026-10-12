package com.rydex.driver.matching;

import com.rydex.common.geo.GeoPoint;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Client for the OSRM {@code table} service: driving durations from many drivers to one pickup in one call. */
public class OsrmTableClient {

    private final RestClient http;

    public OsrmTableClient(RestClient http) {
        this.http = http;
    }

    /**
     * Driving time in seconds from each source to {@code destination}, in source order.
     * An entry is null when OSRM finds no route for that source.
     */
    public Double[] durationsTo(List<GeoPoint> sources, GeoPoint destination) {
        if (sources.isEmpty()) {
            return new Double[0];
        }
        String coords = Stream.concat(sources.stream(), Stream.of(destination))
                .map(p -> String.format(Locale.ROOT, "%.6f,%.6f", p.lng(), p.lat()))
                .collect(Collectors.joining(";"));
        String sourceIdx = IntStream.range(0, sources.size()).mapToObj(Integer::toString).collect(Collectors.joining(";"));
        JsonNode body = http.get()
                // literal path: OSRM needs ',' and ';' unescaped (both are legal in a URI path)
                .uri("/table/v1/driving/" + coords + "?sources=" + sourceIdx + "&destinations=" + sources.size()
                        + "&annotations=duration")
                .retrieve()
                .body(JsonNode.class);
        if (body == null || !"Ok".equals(body.path("code").asString())) {
            throw new IllegalStateException("OSRM table returned " + (body == null ? "no body" : body.path("code").asString()));
        }
        JsonNode rows = body.path("durations");
        if (rows.size() != sources.size()) {
            throw new IllegalStateException("OSRM table returned " + rows.size() + " rows for " + sources.size() + " sources");
        }
        Double[] result = new Double[sources.size()];
        for (int i = 0; i < result.length; i++) {
            JsonNode cell = rows.get(i).get(0);
            result[i] = cell == null || cell.isNull() ? null : cell.asDouble();
        }
        return result;
    }
}
