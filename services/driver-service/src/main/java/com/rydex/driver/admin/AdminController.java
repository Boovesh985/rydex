package com.rydex.driver.admin;

import com.rydex.common.DriverStatus;
import com.rydex.common.RedisKeys;
import com.rydex.common.RideType;
import com.rydex.common.geo.GeoPoint;
import com.rydex.common.geo.Geohash;
import com.rydex.driver.driver.DriverDtos.DriverView;
import com.rydex.driver.driver.DriverService;
import com.rydex.driver.matching.Candidate;
import com.rydex.driver.matching.CandidateFinder;
import com.rydex.driver.matching.CandidateRanker;
import com.rydex.driver.matching.MatchingDtos.JobView;
import com.rydex.driver.matching.MatchingService;
import com.rydex.driver.presence.PresenceProperties;
import com.rydex.driver.web.ApiException;
import com.rydex.presence.DriverLocation;
import com.rydex.presence.PresenceStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin views of the marketplace (geohash grid, live drivers, matching internals) and driver recovery. ADMIN only. */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin", description = "Geohash grid, live drivers, matching diagnostics and driver recovery (ADMIN role)")
class AdminController {

    /** Labels for {@link Geohash#grid3x3}: centre, then N, NE, E, SE, S, SW, W, NW. */
    private static final List<String> POSITIONS = List.of("C", "N", "NE", "E", "SE", "S", "SW", "W", "NW");
    private static final int LIVE_LIMIT = 2000;

    private final PresenceStore presence;
    private final PresenceProperties presenceProps;
    private final CandidateFinder finder;
    private final CandidateRanker ranker;
    private final MatchingService matching;
    private final DriverService drivers;
    private final StringRedisTemplate redis;
    private final Clock clock;

    AdminController(PresenceStore presence, PresenceProperties presenceProps, CandidateFinder finder,
                    CandidateRanker ranker, MatchingService matching, DriverService drivers, StringRedisTemplate redis,
                    Clock clock) {
        this.presence = presence;
        this.presenceProps = presenceProps;
        this.finder = finder;
        this.ranker = ranker;
        this.matching = matching;
        this.drivers = drivers;
        this.redis = redis;
        this.clock = clock;
    }

    public record GridCell(String geohash, String position, double minLat, double minLng, double maxLat, double maxLng,
                           long availableDrivers, long recentRequests) {
    }

    public record Grid(String center, int precision, long availableDrivers, long recentRequests, List<GridCell> cells) {
    }

    @GetMapping("/grid")
    @Operation(summary = "The 3x3 geohash-6 cells around a point, with available drivers and ride requests "
            + "(current + previous 15-minute bucket) per cell")
    Grid grid(@RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
              @RequestParam @DecimalMin("-180") @DecimalMax("180") double lng) {
        String center = Geohash.encode(lat, lng, Geohash.MATCHING_PRECISION);
        long bucket = RedisKeys.demandBucket(clock.instant());
        List<String> cells = Geohash.grid3x3(center);
        List<GridCell> result = new ArrayList<>(cells.size());
        for (int i = 0; i < cells.size(); i++) {
            String cell = cells.get(i);
            double[] b = Geohash.bounds(cell);
            result.add(new GridCell(cell, POSITIONS.get(i), b[0], b[1], b[2], b[3],
                    presence.supply(cell), counter(RedisKeys.demand(cell, bucket)) + counter(RedisKeys.demand(cell, bucket - 1))));
        }
        return new Grid(center, Geohash.MATCHING_PRECISION,
                result.stream().mapToLong(GridCell::availableDrivers).sum(),
                result.stream().mapToLong(GridCell::recentRequests).sum(),
                result);
    }

    public record LiveDriver(UUID driverId, double lat, double lng, Double heading, Double speed, String geohash,
                             DriverStatus status, RideType rideType, Instant at) {
    }

    @GetMapping("/drivers/live")
    @Operation(summary = "Every driver that sent a heartbeat recently, with status and ride type")
    List<LiveDriver> liveDrivers() {
        Instant since = clock.instant().minus(presenceProps.staleAfter());
        List<LiveDriver> result = new ArrayList<>();
        for (UUID id : presence.seenSince(since, LIVE_LIMIT)) {
            DriverLocation loc = presence.location(id).orElse(null);
            if (loc == null) {
                continue;
            }
            var state = presence.state(id).orElse(null);
            result.add(new LiveDriver(id, loc.lat(), loc.lng(), loc.heading(), loc.speed(), loc.geohash(),
                    state == null ? null : state.status(), state == null ? null : state.type(), loc.at()));
        }
        return result;
    }

    @GetMapping("/matching/candidates")
    @Operation(summary = "Dry run: who would be offered a ride from this pickup, in order (no offer is made)")
    List<Candidate.Ranked> candidates(@RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
                                      @RequestParam @DecimalMin("-180") @DecimalMax("180") double lng,
                                      @RequestParam RideType rideType) {
        GeoPoint pickup = GeoPoint.of(lat, lng);
        return ranker.rank(pickup, finder.find(rideType, pickup, Set.of()));
    }

    @GetMapping("/matching/{rideId}")
    @Operation(summary = "A ride's matching job and every offer it made")
    JobView job(@PathVariable UUID rideId) {
        return matching.job(rideId).orElseThrow(() -> ApiException.notFound("Matching job"));
    }

    @GetMapping("/drivers")
    @Operation(summary = "Find drivers by name or plate (at least 2 letters; plates match with spaces ignored)")
    List<DriverView> search(@RequestParam String q) {
        return drivers.search(q);
    }

    @GetMapping("/drivers/{driverId}")
    @Operation(summary = "One driver: profile, vehicle, status, current ride and last position")
    DriverView driver(@PathVariable UUID driverId) {
        return drivers.byId(driverId);
    }

    @PostMapping("/drivers/{driverId}/release")
    @Operation(summary = "Free a driver stuck ON_TRIP whose ride is already over (they go OFFLINE)")
    DriverView release(@PathVariable UUID driverId) {
        return drivers.release(driverId);
    }

    private long counter(String key) {
        String v = redis.opsForValue().get(key);
        return v == null ? 0 : Long.parseLong(v);
    }
}
