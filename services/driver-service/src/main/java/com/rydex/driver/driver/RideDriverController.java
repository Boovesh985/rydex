package com.rydex.driver.driver;

import com.rydex.driver.driver.DriverDtos.DriverCard;
import com.rydex.driver.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The driver card on the rider's live-ride screen. */
@RestController
@Tag(name = "Driver")
class RideDriverController {

    private final DriverService service;

    RideDriverController(DriverService service) {
        this.service = service;
    }

    @GetMapping("/api/drivers/for-ride/{rideId}")
    @Operation(summary = "Name, vehicle and rating of the driver assigned to a ride (its rider, that driver or an admin)")
    DriverCard forRide(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID rideId) {
        return service.cardForRide(AuthUser.from(jwt), rideId);
    }
}
