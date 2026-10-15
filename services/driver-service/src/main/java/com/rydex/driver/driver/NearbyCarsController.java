package com.rydex.driver.driver;

import com.rydex.common.RideType;
import com.rydex.common.geo.GeoPoint;
import com.rydex.driver.matching.Candidate;
import com.rydex.driver.matching.CandidateFinder;
import com.rydex.presence.DriverLocation;
import com.rydex.presence.PresenceStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The free cars around a rider's pickup, as ride apps show them on the booking map (ADR 0016).
 * Drivers are not identified: each car has an opaque id (an HMAC of the driver id with a key made
 * at startup) that stays the same between polls, so the map can glide it, but means nothing else.
 */
@RestController
@Tag(name = "Driver")
class NearbyCarsController {

    static final double RADIUS_KM = 3.0;
    static final int PER_TYPE = 6;

    private final CandidateFinder finder;
    private final PresenceStore presence;
    private final SecretKeySpec idKey;

    NearbyCarsController(CandidateFinder finder, PresenceStore presence) {
        this.finder = finder;
        this.presence = presence;
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        this.idKey = new SecretKeySpec(key, "HmacSHA256");
    }

    /**
     * @param id        opaque, stable while this instance runs
     * @param distanceM straight-line distance from the point asked about
     */
    record NearbyCar(String id, double lat, double lng, Double heading, RideType rideType, int distanceM) {
    }

    @GetMapping("/api/drivers/nearby")
    @Operation(summary = "Free cars within 3 km of a point (up to 6 per ride type), without driver identities")
    List<NearbyCar> nearby(@RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
                           @RequestParam @DecimalMin("-180") @DecimalMax("180") double lng) {
        GeoPoint at = GeoPoint.of(lat, lng);
        List<NearbyCar> cars = new ArrayList<>();
        for (RideType type : RideType.values()) {
            for (Candidate c : finder.within(type, at, RADIUS_KM, Set.of()).stream().limit(PER_TYPE).toList()) {
                DriverLocation loc = presence.location(c.driverId()).orElse(null);
                double carLat = loc != null ? loc.lat() : c.position().lat();
                double carLng = loc != null ? loc.lng() : c.position().lng();
                cars.add(new NearbyCar(opaque(c.driverId()), carLat, carLng, loc != null ? loc.heading() : null, type,
                        c.distanceM()));
            }
        }
        cars.sort(Comparator.comparingInt(NearbyCar::distanceM));
        return cars;
    }

    private String opaque(UUID driverId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(idKey);
            byte[] digest = mac.doFinal(driverId.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
