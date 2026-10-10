package com.rydex.ride.geo;

import java.util.List;

/**
 * A driving route. {@code polyline} is Google-encoded polyline with precision 6 (OSRM
 * {@code polyline6}); it is null when the route is a straight-line estimate.
 *
 * @param source       OSRM, or ESTIMATED when routing was unavailable
 * @param alternatives other routes OSRM offered (used by the AI route choice)
 */
public record RouteResult(int distanceM, int durationS, String polyline, Source source, List<Alternative> alternatives) {

    public enum Source {
        OSRM,
        ESTIMATED
    }

    public record Alternative(int distanceM, int durationS, String polyline) {
    }
}
