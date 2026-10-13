package com.rydex.ride.ride;

import com.rydex.common.RedisKeys;
import com.rydex.common.geo.GeoPoint;
import com.rydex.events.EventEnvelope;
import com.rydex.events.EventType;
import com.rydex.events.RidePayload;
import com.rydex.messaging.OutboxWriter;
import com.rydex.ride.pricing.MarketplaceStore;
import com.rydex.ride.ride.RideDtos.BookRideRequest;
import com.rydex.ride.ride.RideDtos.PageView;
import com.rydex.ride.ride.RideDtos.RideView;
import com.rydex.ride.ride.RideDtos.StatusChangeView;
import com.rydex.ride.security.AuthUser;
import com.rydex.ride.user.Role;
import com.rydex.ride.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Ride lifecycle. Every state change is persisted together with its history row and
 * its outbox event in one transaction.
 */
@Service
public class RideService {

    static final int MAX_OTP_ATTEMPTS = 5;
    private static final Set<RideStatus> OTP_VISIBLE =
            EnumSet.of(RideStatus.REQUESTED, RideStatus.DRIVER_ASSIGNED, RideStatus.DRIVER_ARRIVED);

    private final RideRepository rides;
    private final RideStatusChangeRepository history;
    private final EstimateService estimates;
    private final OtpService otps;
    private final OutboxWriter outbox;
    private final MarketplaceStore market;
    private final StringRedisTemplate redis;
    private final Clock clock;

    RideService(RideRepository rides, RideStatusChangeRepository history, EstimateService estimates, OtpService otps,
                OutboxWriter outbox, MarketplaceStore market, StringRedisTemplate redis, Clock clock) {
        this.rides = rides;
        this.history = history;
        this.estimates = estimates;
        this.otps = otps;
        this.outbox = outbox;
        this.market = market;
        this.redis = redis;
        this.clock = clock;
    }

    @Transactional
    public RideView book(AuthUser rider, BookRideRequest req) {
        requireRole(rider, Role.RIDER);
        GeoPoint pickup = req.pickup().toGeoPoint();
        GeoPoint drop = req.drop().toGeoPoint();
        Quote quote = estimates.quote(pickup, drop, req.rideType());
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        String otp = otps.otpFor(id);

        Ride ride = Ride.request(id, rider.id(), req.rideType(), pickup, drop, quote, otps.hash(id, otp),
                req.suggestionId(), now);
        try {
            rides.saveAndFlush(ride);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("You already have a ride in progress");
        }
        record(ride, null, rider.actor(), now);
        publish(EventType.RIDE_REQUESTED, ride, null);
        afterCommit(() -> market.recordDemand(pickup, now));
        return RideView.of(ride, otp, null);
    }

    @Transactional(readOnly = true)
    public RideView get(AuthUser caller, UUID rideId) {
        Ride ride = load(rideId);
        requireAccess(caller, ride);
        List<StatusChangeView> timeline = history.findByRideIdOrderByAtAscIdAsc(rideId).stream()
                .map(h -> new StatusChangeView(h.getFromStatus(), h.getToStatus(), h.getAt()))
                .toList();
        return RideView.of(ride, otpFor(caller, ride), timeline);
    }

    @Transactional(readOnly = true)
    public PageView<RideView> list(AuthUser caller, boolean mine, int page, int size) {
        var pageable = PageRequest.of(page, size);
        Page<Ride> result = switch (caller.role()) {
            case RIDER -> rides.findByRiderIdOrderByRequestedAtDesc(caller.id(), pageable);
            case DRIVER -> rides.findByDriverIdOrderByRequestedAtDesc(caller.id(), pageable);
            case ADMIN -> mine ? Page.empty(pageable) : rides.findAllByOrderByRequestedAtDesc(pageable);
        };
        return new PageView<>(result.map(r -> RideView.of(r, otpFor(caller, r), null)).getContent(),
                page, size, result.getTotalElements());
    }

    @Transactional
    public RideView cancel(AuthUser caller, UUID rideId, String reason) {
        Ride ride = load(rideId);
        requireAccess(caller, ride);
        RideStatus from = ride.getStatus();
        Instant now = clock.instant();
        ride.cancel(caller.role().name(), reason == null || reason.isBlank() ? null : reason.trim(), now);
        record(ride, from, caller.actor(), now);
        publish(EventType.RIDE_CANCELLED, ride, ride.getCancelReason());
        return RideView.of(ride, null, null);
    }

    @Transactional
    public RideView markArrived(AuthUser driver, UUID rideId) {
        Ride ride = loadForAssignedDriver(driver, rideId);
        RideStatus from = ride.getStatus();
        Instant now = clock.instant();
        ride.markDriverArrived(now);
        record(ride, from, driver.actor(), now);
        publish(EventType.DRIVER_ARRIVED, ride, null);
        return RideView.of(ride, null, null);
    }

    @Transactional
    public RideView start(AuthUser driver, UUID rideId, String otp) {
        Ride ride = loadForAssignedDriver(driver, rideId);
        if (ride.getStatus() != RideStatus.DRIVER_ARRIVED) {
            throw new IllegalRideTransitionException(ride.getStatus(), RideStatus.IN_PROGRESS);
        }
        String attemptsKey = RedisKeys.otpAttempts(rideId);
        Long attempts = redis.opsForValue().increment(attemptsKey);
        redis.expire(attemptsKey, Duration.ofHours(1));
        if (attempts != null && attempts > MAX_OTP_ATTEMPTS) {
            throw ApiException.tooManyRequests("Too many incorrect OTP attempts; ask the rider to contact support", 3600);
        }
        if (!otps.matches(rideId, otp, ride.getOtpHash())) {
            throw ApiException.unprocessable("Incorrect OTP");
        }
        RideStatus from = ride.getStatus();
        Instant now = clock.instant();
        ride.start(now);
        record(ride, from, driver.actor(), now);
        publish(EventType.RIDE_STARTED, ride, null);
        afterCommit(() -> redis.delete(attemptsKey));
        return RideView.of(ride, null, null);
    }

    @Transactional
    public RideView complete(AuthUser driver, UUID rideId) {
        Ride ride = loadForAssignedDriver(driver, rideId);
        RideStatus from = ride.getStatus();
        Instant now = clock.instant();
        ride.complete(now);
        record(ride, from, driver.actor(), now);
        publish(EventType.RIDE_COMPLETED, ride, null);
        return RideView.of(ride, null, null);
    }

    /** Applied when driver-service reports a match (DRIVER_ASSIGNED event). */
    @Transactional
    public void assignDriver(UUID rideId, UUID driverId, String actor) {
        Ride ride = load(rideId);
        RideStatus from = ride.getStatus();
        Instant now = clock.instant();
        ride.assignDriver(driverId, now);
        record(ride, from, actor, now);
    }

    /** Applied when driver-service gives up matching (NO_DRIVER_FOUND event). */
    @Transactional
    public void markNoDriverFound(UUID rideId, String actor) {
        Ride ride = load(rideId);
        RideStatus from = ride.getStatus();
        Instant now = clock.instant();
        ride.markNoDriverFound(now);
        record(ride, from, actor, now);
    }

    private Ride load(UUID rideId) {
        return rides.findById(rideId).orElseThrow(() -> ApiException.notFound("Ride"));
    }

    private Ride loadForAssignedDriver(AuthUser driver, UUID rideId) {
        requireRole(driver, Role.DRIVER);
        Ride ride = load(rideId);
        if (!driver.id().equals(ride.getDriverId())) {
            throw ApiException.forbidden("You are not the driver assigned to this ride");
        }
        return ride;
    }

    private static void requireRole(AuthUser user, Role role) {
        if (!user.is(role)) {
            throw ApiException.forbidden("Only a " + role.name().toLowerCase() + " can do this");
        }
    }

    /** Riders see their rides, drivers the rides assigned to them, admins everything. Others get 404, not 403. */
    private static void requireAccess(AuthUser caller, Ride ride) {
        boolean allowed = caller.is(Role.ADMIN)
                || caller.id().equals(ride.getRiderId())
                || caller.id().equals(ride.getDriverId());
        if (!allowed) {
            throw ApiException.notFound("Ride");
        }
    }

    private String otpFor(AuthUser caller, Ride ride) {
        return caller.id().equals(ride.getRiderId()) && OTP_VISIBLE.contains(ride.getStatus())
                ? otps.otpFor(ride.getId())
                : null;
    }

    private void record(Ride ride, RideStatus from, String actor, Instant at) {
        history.save(new RideStatusChange(ride.getId(), from, ride.getStatus(), at, actor));
    }

    private void publish(EventType type, Ride ride, String reason) {
        var payload = new RidePayload(ride.getRiderId(), ride.getDriverId(), ride.getRideType(), ride.pickup(),
                ride.drop(), ride.getFinalFare() != null ? ride.getFinalFare() : ride.getEstFare(),
                ride.getEstDistanceM(), ride.getEstDurationS(), reason);
        outbox.append(EventEnvelope.of(type, ride.getId().toString(), payload, clock));
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
