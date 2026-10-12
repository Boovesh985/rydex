package com.rydex.driver.driver;

import com.rydex.driver.driver.DriverDtos.Availability;
import com.rydex.driver.driver.DriverDtos.DriverView;
import com.rydex.driver.driver.DriverDtos.LocationRequest;
import com.rydex.driver.driver.DriverDtos.StatusRequest;
import com.rydex.driver.driver.DriverDtos.UpsertProfileRequest;
import com.rydex.driver.matching.MatchingService;
import com.rydex.driver.presence.DriverLocation;
import com.rydex.driver.presence.PresenceStore;
import com.rydex.driver.presence.PresenceStore.HeartbeatResult;
import com.rydex.driver.security.AuthUser;
import com.rydex.driver.support.AfterCommit;
import com.rydex.driver.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Driver onboarding, the online/offline toggle and location heartbeats. */
@Service
public class DriverService {

    private final DriverRepository drivers;
    private final PresenceStore presence;
    private final MatchingService matching;
    private final Clock clock;

    public DriverService(DriverRepository drivers, PresenceStore presence, MatchingService matching, Clock clock) {
        this.drivers = drivers;
        this.presence = presence;
        this.matching = matching;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DriverView me(AuthUser user) {
        return view(load(user));
    }

    /** Creates the profile on first call (onboarding), updates the vehicle afterwards. */
    @Transactional
    public DriverView upsertProfile(AuthUser user, UpsertProfileRequest req) {
        requireDriver(user);
        String number = req.vehicleNumber().trim().toUpperCase();
        if (drivers.existsByVehicleNumberIgnoreCaseAndIdNot(number, user.id())) {
            throw ApiException.conflict("Vehicle number " + number + " is already registered");
        }
        Instant now = clock.instant();
        Driver driver = drivers.findById(user.id()).orElse(null);
        if (driver == null) {
            String name = user.name() == null || user.name().isBlank() ? "Driver" : user.name();
            driver = drivers.save(Driver.register(user.id(), name, req.vehicleType(), number, req.vehicleModel().trim(), now));
        } else {
            try {
                driver.updateVehicle(user.name(), req.vehicleType(), number, req.vehicleModel().trim(), now);
            } catch (IllegalStateException e) {
                throw ApiException.conflict(e.getMessage());
            }
        }
        drivers.flush();
        return DriverView.of(driver, false, presence.location(driver.getId()).orElse(null));
    }

    /** Go online (AVAILABLE) or offline. Going offline turns down a pending offer. */
    @Transactional
    public DriverView setStatus(AuthUser user, StatusRequest req) {
        if ((req.lat() == null) != (req.lng() == null)) {
            throw ApiException.badRequest("lat and lng must be given together");
        }
        Driver driver = load(user);
        boolean available = req.status() == Availability.AVAILABLE;
        try {
            driver.setAvailability(available, clock.instant());
        } catch (IllegalStateException e) {
            throw ApiException.conflict(e.getMessage());
        }
        if (!available) {
            matching.withdrawPendingOffer(driver.getId());
        }
        drivers.flush();
        Instant now = clock.instant();
        AfterCommit.run(() -> {
            if (req.lat() != null) {
                presence.heartbeat(driver.getId(), req.lat(), req.lng(), null, null, now);
            }
            presence.setStatus(driver.getId(), driver.getStatus(), driver.getVehicleType());
        });
        DriverLocation loc = req.lat() == null ? presence.location(driver.getId()).orElse(null)
                : new DriverLocation(driver.getId(), req.lat(), req.lng(), null, null, null, now);
        return DriverView.of(driver, available && loc != null, loc);
    }

    /**
     * Location heartbeat. Hot path: touches only Redis unless the availability projection is
     * missing there, in which case it is rebuilt from Postgres.
     */
    public void reportLocation(AuthUser user, LocationRequest req) {
        requireDriver(user);
        if (!presence.tryClaimLocationSlot(user.id())) {
            throw ApiException.tooManyRequests("Location updates are too frequent; send one every few seconds", 1);
        }
        Instant now = clock.instant();
        HeartbeatResult result = presence.heartbeat(user.id(), req.lat(), req.lng(), req.heading(), req.speed(), now);
        if (result == HeartbeatResult.STATE_MISSING) {
            Driver driver = load(user);
            presence.setStatus(driver.getId(), driver.getStatus(), driver.getVehicleType());
        }
    }

    private DriverView view(Driver d) {
        DriverLocation loc = presence.location(d.getId()).orElse(null);
        boolean searchable = d.getStatus() == DriverStatus.AVAILABLE && loc != null && presence.isAvailable(d.getId());
        return DriverView.of(d, searchable, loc);
    }

    private Driver load(AuthUser user) {
        requireDriver(user);
        return drivers.findById(user.id()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Not found",
                "Driver profile not found; create it with PUT /api/drivers/me"));
    }

    private static void requireDriver(AuthUser user) {
        if (!user.isDriver()) {
            throw ApiException.forbidden("Only drivers can do this");
        }
    }
}
