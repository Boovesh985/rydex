package com.rydex.ride.geo;

import com.rydex.common.geo.GeoPoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/places")
@Tag(name = "Places")
class PlacesController {

    private final PlacesService places;
    private final RoutingService routing;

    PlacesController(PlacesService places, RoutingService routing) {
        this.places = places;
        this.routing = routing;
    }

    /** {@code polyline} is OSRM polyline6, null when {@code source} is ESTIMATED (straight line). */
    public record RouteView(int distanceM, int durationS, String polyline, RouteResult.Source source) {
    }

    @GetMapping("/search")
    @Operation(summary = "Search places (OpenStreetMap Nominatim, cached). lat/lng bias results towards the user.")
    List<Place> search(
            @RequestParam @Size(min = 3, max = 200) String q,
            @RequestParam(required = false) @DecimalMin("-90") @DecimalMax("90") Double lat,
            @RequestParam(required = false) @DecimalMin("-180") @DecimalMax("180") Double lng) {
        return places.search(q, lat, lng);
    }

    @GetMapping("/reverse")
    @Operation(summary = "Address for a coordinate")
    Place reverse(
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double lng) {
        return places.reverse(lat, lng);
    }

    @GetMapping("/route")
    @Operation(summary = "Driving route between two points (OSRM, cached): the live-ride and driver navigation maps")
    RouteView route(
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double fromLat,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double fromLng,
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double toLat,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double toLng) {
        RouteResult r = routing.route(GeoPoint.of(fromLat, fromLng), GeoPoint.of(toLat, toLng));
        return new RouteView(r.distanceM(), r.durationS(), r.polyline(), r.source());
    }
}
