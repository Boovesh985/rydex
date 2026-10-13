package com.rydex.tracking.location;

import com.rydex.tracking.security.AuthUser;
import java.security.Principal;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

/** STOMP {@code /app/driver.location} (drivers only, enforced by {@code StompAuthInterceptor}). */
@Controller
class LocationController {

    private final LocationService locations;

    LocationController(LocationService locations) {
        this.locations = locations;
    }

    @MessageMapping("driver.location")
    void location(@Payload LocationUpdate update, Principal principal) {
        locations.ingest((AuthUser) principal, update);
    }
}
