package com.rydex.ride.ride;

import com.rydex.ride.ride.RideDtos.BookRideRequest;
import com.rydex.ride.ride.RideDtos.CancelRequest;
import com.rydex.ride.ride.RideDtos.EstimateRequest;
import com.rydex.ride.ride.RideDtos.EstimateResponse;
import com.rydex.ride.ride.RideDtos.PageView;
import com.rydex.ride.ride.RideDtos.RideView;
import com.rydex.ride.ride.RideDtos.StartRideRequest;
import com.rydex.ride.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rides")
@Tag(name = "Rides")
class RideController {

    private final RideService rides;
    private final EstimateService estimates;
    private final IdempotencyStore idempotency;

    RideController(RideService rides, EstimateService estimates, IdempotencyStore idempotency) {
        this.rides = rides;
        this.estimates = estimates;
        this.idempotency = idempotency;
    }

    @PostMapping("/estimate")
    @PreAuthorize("hasRole('RIDER')")
    @Operation(summary = "Fares and driver ETAs for every ride type")
    EstimateResponse estimate(@Valid @RequestBody EstimateRequest req) {
        return estimates.estimate(req.pickup().toGeoPoint(), req.drop().toGeoPoint());
    }

    @PostMapping
    @PreAuthorize("hasRole('RIDER')")
    @Operation(summary = "Book a ride. Retrying with the same Idempotency-Key returns the original ride.")
    ResponseEntity<RideView> book(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Unique per booking attempt, e.g. a UUID")
            @RequestHeader(name = "Idempotency-Key", required = false)
            @Pattern(regexp = "[A-Za-z0-9_-]{8,64}", message = "must be 8-64 characters of [A-Za-z0-9_-]")
            String idempotencyKey,
            @Valid @RequestBody BookRideRequest req) {
        AuthUser rider = AuthUser.from(jwt);
        if (idempotencyKey == null) {
            return created(rides.book(rider, req), false);
        }
        Optional<UUID> existing = idempotency.begin(rider.id(), idempotencyKey);
        if (existing.isPresent()) {
            return created(rides.get(rider, existing.get()), true);
        }
        try {
            RideView ride = rides.book(rider, req);
            idempotency.complete(rider.id(), idempotencyKey, ride.id());
            return created(ride, false);
        } catch (RuntimeException e) {
            idempotency.abort(rider.id(), idempotencyKey);
            throw e;
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "A ride with its status timeline")
    RideView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return rides.get(AuthUser.from(jwt), id);
    }

    @GetMapping
    @Operation(summary = "Ride history, newest first. Riders/drivers always see their own rides; admins see all "
            + "unless mine=true, or only the rides in flight with active=true.")
    PageView<RideView> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "true") boolean mine,
            @RequestParam(defaultValue = "false") boolean active,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return rides.list(AuthUser.from(jwt), mine, active, page, size);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a ride that has not started")
    RideView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                    @Valid @RequestBody(required = false) CancelRequest req) {
        return rides.cancel(AuthUser.from(jwt), id, req == null ? null : req.reason());
    }

    @PostMapping("/{id}/arrived")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "Driver has reached the pickup point")
    RideView arrived(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return rides.markArrived(AuthUser.from(jwt), id);
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "Start the trip after checking the rider's 4-digit OTP")
    RideView start(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody StartRideRequest req) {
        return rides.start(AuthUser.from(jwt), id, req.otp());
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "Finish the trip")
    RideView complete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return rides.complete(AuthUser.from(jwt), id);
    }

    private static ResponseEntity<RideView> created(RideView ride, boolean replayed) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/rides/" + ride.id()))
                .header("Idempotent-Replayed", String.valueOf(replayed))
                .body(ride);
    }
}
