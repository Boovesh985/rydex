package com.rydex.driver.matching;

import com.rydex.common.geo.GeoPoint;
import java.math.BigDecimal;
import java.util.UUID;

/** An available driver near a pickup, as returned by GEOSEARCH. */
public record Candidate(UUID driverId, GeoPoint position, int distanceM) {

    /**
     * A candidate after ranking.
     *
     * @param etaS         driving time to the pickup
     * @param etaEstimated true when OSRM was unavailable and the ETA is a straight-line estimate
     */
    public record Ranked(UUID driverId, GeoPoint position, int distanceM, int etaS, boolean etaEstimated,
                         BigDecimal rating) {
    }
}
