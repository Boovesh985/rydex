package com.rydex.driver.driver;

import com.rydex.driver.driver.DriverDtos.DriverView;
import com.rydex.driver.driver.DriverDtos.LocationRequest;
import com.rydex.driver.driver.DriverDtos.StatusRequest;
import com.rydex.driver.driver.DriverDtos.UpsertProfileRequest;
import com.rydex.driver.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/drivers/me")
@Tag(name = "Driver", description = "Profile, availability and location of the signed-in driver")
class DriverController {

    private final DriverService service;

    DriverController(DriverService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Your driver profile, status and last known position")
    DriverView me(@AuthenticationPrincipal Jwt jwt) {
        return service.me(AuthUser.from(jwt));
    }

    @PutMapping
    @Operation(summary = "Create (onboarding) or update your vehicle details")
    DriverView upsert(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpsertProfileRequest req) {
        return service.upsertProfile(AuthUser.from(jwt), req);
    }

    @PatchMapping("/status")
    @Operation(summary = "Go online (AVAILABLE) or OFFLINE; optionally send your position with it")
    DriverView status(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StatusRequest req) {
        return service.setStatus(AuthUser.from(jwt), req);
    }

    @PostMapping("/location")
    @Operation(summary = "Location heartbeat (every ~3 s while online; at most 2 per second)")
    ResponseEntity<Void> location(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LocationRequest req) {
        service.reportLocation(AuthUser.from(jwt), req);
        return ResponseEntity.noContent().build();
    }
}
