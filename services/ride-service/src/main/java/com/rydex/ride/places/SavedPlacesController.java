package com.rydex.ride.places;

import com.rydex.common.geo.GeoPoint;
import com.rydex.ride.ride.RideDtos.LocationInput;
import com.rydex.ride.security.AuthUser;
import com.rydex.ride.user.Role;
import com.rydex.ride.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** A rider's Home and Work, for one-tap bookings (saved on the server, so they follow the rider). */
@RestController
@RequestMapping("/api/me/places")
@Tag(name = "Users")
class SavedPlacesController {

    record SavedPlaceView(SavedPlace.Kind kind, GeoPoint place) {
    }

    private final SavedPlaceRepository places;
    private final JsonMapper json;
    private final Clock clock;

    SavedPlacesController(SavedPlaceRepository places, JsonMapper json, Clock clock) {
        this.places = places;
        this.json = json;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "Your saved places (Home, Work)")
    @Transactional(readOnly = true)
    List<SavedPlaceView> list(@AuthenticationPrincipal Jwt jwt) {
        return places.findByUserIdOrderByKind(rider(jwt)).stream().map(this::view).toList();
    }

    @PutMapping("/{kind}")
    @Operation(summary = "Save (or move) your Home or Work")
    @Transactional
    SavedPlaceView save(@AuthenticationPrincipal Jwt jwt, @PathVariable SavedPlace.Kind kind, @Valid @RequestBody LocationInput at) {
        UUID user = rider(jwt);
        GeoPoint point = at.toGeoPoint();
        String place = json.writeValueAsString(point);
        SavedPlace saved = places.findById(new SavedPlace.Key(user, kind))
                .map(p -> {
                    p.moveTo(place, clock.instant());
                    return p;
                })
                .orElseGet(() -> places.save(new SavedPlace(user, kind, place, clock.instant())));
        return view(saved);
    }

    @DeleteMapping("/{kind}")
    @Operation(summary = "Forget your Home or Work")
    @Transactional
    ResponseEntity<Void> forget(@AuthenticationPrincipal Jwt jwt, @PathVariable SavedPlace.Kind kind) {
        places.deleteById(new SavedPlace.Key(rider(jwt), kind));
        return ResponseEntity.noContent().build();
    }

    private SavedPlaceView view(SavedPlace p) {
        return new SavedPlaceView(p.getKind(), json.readValue(p.getPlace(), GeoPoint.class));
    }

    private static UUID rider(Jwt jwt) {
        AuthUser user = AuthUser.from(jwt);
        if (!user.is(Role.RIDER)) {
            throw ApiException.forbidden("Only a rider has saved places");
        }
        return user.id();
    }
}
