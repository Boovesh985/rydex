package com.rydex.ride.ai;

import com.rydex.common.RideType;
import com.rydex.common.geo.GeoPoint;
import com.rydex.ride.ai.AiDtos.Option;
import com.rydex.ride.ai.AiDtos.PastRide;
import com.rydex.ride.ai.AiDtos.Point;
import com.rydex.ride.ai.AiDtos.RouteOption;
import com.rydex.ride.ai.AiDtos.Suggestion;
import com.rydex.ride.ai.AiDtos.SuggestionRequest;
import com.rydex.ride.geo.RouteResult;
import com.rydex.ride.pricing.FareCalculator;
import com.rydex.ride.pricing.FareProperties;
import com.rydex.ride.ride.RideDtos.RideOption;
import com.rydex.ride.user.User;
import com.rydex.ride.user.UserRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Asks ai-service for a smart suggestion to show with a rider's estimate. Never in the way of a
 * booking: a slow (over 300 ms), failing or switched-off ai-service just means no suggestion, and
 * after repeated failures the circuit breaker stops asking for a while.
 */
@Service
public class SuggestionService {

    private static final Logger log = LoggerFactory.getLogger(SuggestionService.class);
    static final int HISTORY = 20;

    private final AiClient client;
    private final AiProperties props;
    private final SuggestionLog suggestionLog;
    private final FareCalculator fares;
    private final FareProperties fareProps;
    private final UserRepository users;
    private final JdbcClient jdbc;
    private final CircuitBreaker breaker;

    SuggestionService(AiClient client, AiProperties props, SuggestionLog suggestionLog, FareCalculator fares,
                      FareProperties fareProps, UserRepository users, JdbcClient jdbc, CircuitBreakerRegistry breakers) {
        this.client = client;
        this.props = props;
        this.suggestionLog = suggestionLog;
        this.fares = fares;
        this.fareProps = fareProps;
        this.users = users;
        this.jdbc = jdbc;
        this.breaker = breakers.circuitBreaker("ai");
    }

    /** What the estimate already knows: the trip, its quoted options and the market around the pickup. */
    public record Trip(GeoPoint pickup, GeoPoint drop, RouteResult route, BigDecimal surge, long demand, long supply,
                       List<RideOption> options, Instant at) {
    }

    public Optional<Suggestion> suggest(UUID riderId, Trip trip) {
        // the living city's automated riders (ADR 0016) get none: no point predicting and logging for them
        if (!props.enabled() || users.findById(riderId).map(User::isSimulated).orElse(true)) {
            return Optional.empty();
        }
        SuggestionRequest request = request(riderId, trip);
        Suggestion suggestion;
        try {
            suggestion = breaker.executeSupplier(() -> client.suggest(request));
        } catch (RuntimeException e) {
            log.debug("No suggestion from ai-service: {}", NestedExceptionUtils.getMostSpecificCause(e).toString());
            return Optional.empty();
        }
        if (suggestion == null || suggestion.suggestionId() == null || suggestion.recommendedRideType() == null) {
            return Optional.empty();
        }
        try {
            suggestionLog.shown(riderId, request, suggestion);
        } catch (RuntimeException e) {
            log.warn("Could not log suggestion {}: {}", suggestion.suggestionId(), e.toString());
        }
        return Optional.of(suggestion);
    }

    SuggestionRequest request(UUID riderId, Trip trip) {
        RouteResult route = trip.route();
        List<Option> options = trip.options().stream()
                .map(o -> new Option(o.rideType(), o.fare(),
                        fares.fare(o.rideType(), route.distanceM(), route.durationS(), BigDecimal.ONE),
                        o.etaMinutes(), o.driversNearby(), o.upgradeTo()))
                .toList();
        List<RouteOption> routes = new ArrayList<>();
        routes.add(new RouteOption(route.distanceM(), route.durationS(), route.polyline()));
        if (route.alternatives() != null) {
            route.alternatives().forEach(a -> routes.add(new RouteOption(a.distanceM(), a.durationS(), a.polyline())));
        }
        return new SuggestionRequest(riderId, point(trip.pickup()), point(trip.drop()), trip.at(), route.distanceM(),
                route.durationS(), trip.surge(), fareProps.maxSurge(), trip.demand(), trip.supply(), options, routes,
                history(riderId));
    }

    private List<PastRide> history(UUID riderId) {
        return jdbc.sql("""
                        SELECT ride_type, final_fare FROM rides
                        WHERE rider_id = :rider AND status = 'COMPLETED'
                        ORDER BY completed_at DESC LIMIT :limit
                        """)
                .param("rider", riderId).param("limit", HISTORY)
                .query((rs, i) -> new PastRide(RideType.valueOf(rs.getString("ride_type")), rs.getBigDecimal("final_fare")))
                .list();
    }

    private static Point point(GeoPoint p) {
        return new Point(p.lat(), p.lng());
    }
}
