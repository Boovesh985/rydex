package com.rydex.ride.ride;

import com.rydex.common.RideType;
import com.rydex.common.geo.GeoPoint;
import com.rydex.ride.geo.GeoProperties;
import com.rydex.ride.geo.RouteResult;
import com.rydex.ride.geo.RoutingService;
import com.rydex.ride.pricing.FareCalculator;
import com.rydex.ride.pricing.MarketplaceStore;
import com.rydex.ride.pricing.SurgeCalculator;
import com.rydex.ride.ride.RideDtos.EstimateResponse;
import com.rydex.ride.ride.RideDtos.RideOption;
import com.rydex.ride.ride.RideDtos.RouteView;
import com.rydex.ride.web.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** Fare + ETA for every ride type on a route. The server always recomputes; client prices are never trusted. */
@Service
public class EstimateService {

    static final int MIN_TRIP_METERS = 300;
    static final int MAX_TRIP_METERS = 100_000;
    static final double DRIVER_SEARCH_RADIUS_KM = 5;

    private final RoutingService routing;
    private final MarketplaceStore market;
    private final FareCalculator fares;
    private final SurgeCalculator surgeCalculator;
    private final GeoProperties geo;
    private final Clock clock;

    EstimateService(RoutingService routing, MarketplaceStore market, FareCalculator fares,
                    SurgeCalculator surgeCalculator, GeoProperties geo, Clock clock) {
        this.routing = routing;
        this.market = market;
        this.fares = fares;
        this.surgeCalculator = surgeCalculator;
        this.geo = geo;
        this.clock = clock;
    }

    public EstimateResponse estimate(GeoPoint pickup, GeoPoint drop) {
        validateTrip(pickup, drop);
        RouteResult route = routing.route(pickup, drop);
        BigDecimal surge = currentSurge(pickup);
        List<RideOption> options = new ArrayList<>();
        for (RideType type : RideType.values()) {
            var nearby = market.nearbyDrivers(type, pickup, DRIVER_SEARCH_RADIUS_KM, 10);
            Integer eta = nearby.isEmpty() ? null : etaMinutes(nearby.getFirst().distanceM());
            options.add(new RideOption(type, fares.fare(type, route.distanceM(), route.durationS(), surge), eta,
                    nearby.size()));
        }
        return new EstimateResponse(pickup, drop, route.distanceM(), route.durationS(), surge,
                new RouteView(route.polyline(), route.source().name()), options);
    }

    public Quote quote(GeoPoint pickup, GeoPoint drop, RideType type) {
        validateTrip(pickup, drop);
        RouteResult route = routing.route(pickup, drop);
        BigDecimal surge = currentSurge(pickup);
        return new Quote(fares.fare(type, route.distanceM(), route.durationS(), surge),
                route.distanceM(), route.durationS(), surge);
    }

    private BigDecimal currentSurge(GeoPoint pickup) {
        Instant now = clock.instant();
        return surgeCalculator.surge(market.demandAround(pickup, now), market.supplyAround(pickup));
    }

    /** Driver-to-pickup ETA from straight-line distance; +1 min to get moving. */
    int etaMinutes(int straightLineMeters) {
        double roadMeters = straightLineMeters * geo.roadFactor();
        double minutes = roadMeters / (geo.fallbackSpeedKmh() * 1000 / 60);
        return (int) Math.ceil(minutes) + 1;
    }

    private static void validateTrip(GeoPoint pickup, GeoPoint drop) {
        double d = pickup.distanceMetersTo(drop);
        if (d < MIN_TRIP_METERS) {
            throw ApiException.unprocessable("Pickup and drop are too close together");
        }
        if (d > MAX_TRIP_METERS) {
            throw ApiException.unprocessable("Trips longer than 100 km are not supported");
        }
    }
}
