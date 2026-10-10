package com.rydex.ride.geo;

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

    PlacesController(PlacesService places) {
        this.places = places;
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
}
