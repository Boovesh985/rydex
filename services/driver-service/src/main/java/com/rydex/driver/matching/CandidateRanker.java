package com.rydex.driver.matching;

import com.rydex.common.geo.GeoPoint;
import com.rydex.driver.driver.Driver;
import com.rydex.driver.driver.DriverRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Orders candidates by driving ETA to the pickup (OSRM {@code table}), then by rating, then by
 * distance. ETAs are compared in whole minutes, so a better-rated driver wins over one who is
 * a few seconds closer. If OSRM is down, ETAs fall back to straight-line distance × road factor
 * at an average city speed, and matching carries on.
 */
@Component
public class CandidateRanker {

    private static final Logger log = LoggerFactory.getLogger(CandidateRanker.class);

    static final Comparator<Candidate.Ranked> ORDER = Comparator
            .comparingLong((Candidate.Ranked c) -> Math.round(c.etaS() / 60.0))
            .thenComparing(Candidate.Ranked::rating, Comparator.reverseOrder())
            .thenComparingInt(Candidate.Ranked::distanceM);

    private static final BigDecimal DEFAULT_RATING = new BigDecimal("5.00");

    private final OsrmTableClient osrm;
    private final CircuitBreaker breaker;
    private final DriverRepository drivers;
    private final RoutingProperties props;

    public CandidateRanker(OsrmTableClient osrm, CircuitBreakerRegistry breakers, DriverRepository drivers,
                           RoutingProperties props) {
        this.osrm = osrm;
        this.breaker = breakers.circuitBreaker("osrm-table");
        this.drivers = drivers;
        this.props = props;
    }

    public List<Candidate.Ranked> rank(GeoPoint pickup, List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        Double[] durations = durations(pickup, candidates);
        Map<UUID, BigDecimal> ratings = drivers.findAllById(candidates.stream().map(Candidate::driverId).toList())
                .stream().collect(Collectors.toMap(Driver::getId, Driver::getRating));
        return IntStream.range(0, candidates.size())
                .mapToObj(i -> {
                    Candidate c = candidates.get(i);
                    Double osrmEta = durations == null ? null : durations[i];
                    int eta = osrmEta != null ? (int) Math.round(osrmEta) : estimateEtaS(c.position(), pickup);
                    return new Candidate.Ranked(c.driverId(), c.position(), c.distanceM(), eta, osrmEta == null,
                            ratings.getOrDefault(c.driverId(), DEFAULT_RATING));
                })
                .sorted(ORDER)
                .toList();
    }

    /** Straight-line fallback: distance × road factor at the average city speed. */
    int estimateEtaS(GeoPoint from, GeoPoint to) {
        double roadMeters = from.distanceMetersTo(to) * props.roadFactor();
        return (int) Math.ceil(roadMeters / (props.fallbackSpeedKmh() / 3.6));
    }

    private Double[] durations(GeoPoint pickup, List<Candidate> candidates) {
        try {
            return breaker.executeSupplier(() -> osrm.durationsTo(
                    candidates.stream().map(Candidate::position).toList(), pickup));
        } catch (RuntimeException e) {
            log.warn("OSRM table unavailable, using straight-line ETAs: {}", rootMessage(e));
            return null;
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.toString();
    }
}
