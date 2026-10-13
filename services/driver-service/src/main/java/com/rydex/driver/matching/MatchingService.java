package com.rydex.driver.matching;

import com.rydex.common.RedisKeys;
import com.rydex.common.geo.GeoPoint;
import com.rydex.driver.driver.Driver;
import com.rydex.driver.driver.DriverRepository;
import com.rydex.driver.driver.DriverStatus;
import com.rydex.driver.matching.MatchOffer.Outcome;
import com.rydex.driver.matching.MatchingDtos.AssignmentView;
import com.rydex.driver.matching.MatchingDtos.JobView;
import com.rydex.driver.matching.MatchingDtos.OfferView;
import com.rydex.messaging.OutboxWriter;
import com.rydex.driver.presence.PresenceStore;
import com.rydex.driver.security.AuthUser;
import com.rydex.driver.support.AfterCommit;
import com.rydex.driver.web.ApiException;
import com.rydex.events.EventEnvelope;
import com.rydex.events.EventType;
import com.rydex.events.RidePayload;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Matches a requested ride to a driver (master prompt §7):
 * <ol>
 *   <li>{@link #onRideRequested} stores a job that is due immediately;</li>
 *   <li>the scheduler calls {@link #process} for due jobs: find the nearest available drivers
 *       (GEOSEARCH), rank them by OSRM ETA then rating, lock the best one and offer the ride for
 *       15 s;</li>
 *   <li>the driver accepts (DRIVER_ASSIGNED via the outbox) or rejects / lets it expire, and the
 *       next driver gets an offer. After 5 offers, or when nobody is found for 90 s,
 *       NO_DRIVER_FOUND is emitted.</li>
 * </ol>
 * Postgres holds the job and offer state; Redis holds the locks and the search indexes. Slow
 * calls (OSRM, Redis) run outside database transactions.
 */
@Service
public class MatchingService {

    private static final Logger log = LoggerFactory.getLogger(MatchingService.class);
    static final String ACTOR = "SYSTEM:driver-service";

    private final MatchJobRepository jobs;
    private final MatchOfferRepository offers;
    private final DriverRepository drivers;
    private final CandidateFinder finder;
    private final CandidateRanker ranker;
    private final DriverLocks locks;
    private final PresenceStore presence;
    private final OutboxWriter outbox;
    private final StringRedisTemplate redis;
    private final MatchingProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public MatchingService(MatchJobRepository jobs, MatchOfferRepository offers, DriverRepository drivers,
                           CandidateFinder finder, CandidateRanker ranker, DriverLocks locks, PresenceStore presence,
                           OutboxWriter outbox, StringRedisTemplate redis, MatchingProperties props,
                           TransactionTemplate tx, Clock clock) {
        this.jobs = jobs;
        this.offers = offers;
        this.drivers = drivers;
        this.finder = finder;
        this.ranker = ranker;
        this.locks = locks;
        this.presence = presence;
        this.outbox = outbox;
        this.redis = redis;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ ride events

    /** RIDE_REQUESTED: start matching. Idempotent per ride; returns false for a duplicate. */
    @Transactional
    public boolean onRideRequested(UUID rideId, RidePayload ride) {
        if (jobs.existsById(rideId)) {
            return false;
        }
        jobs.save(MatchJob.create(rideId, ride, clock.instant(), props.searchTimeout()));
        log.info("Matching started for ride {} ({}, pickup {},{})", rideId, ride.rideType(),
                ride.pickup().lat(), ride.pickup().lng());
        return true;
    }

    /** RIDE_CANCELLED: stop matching, or free the assigned driver. Idempotent. */
    @Transactional
    public void onRideCancelled(UUID rideId) {
        Instant now = clock.instant();
        MatchJob job = jobs.findById(rideId).orElse(null);
        if (job != null && job.getStatus().isActive()) {
            for (MatchOffer offer : offers.findByRideIdAndOutcome(rideId, Outcome.PENDING)) {
                offer.close(Outcome.CANCELLED, now);
                AfterCommit.run(() -> locks.release(offer.getDriverId(), rideId));
            }
            job.cancel(now);
            AfterCommit.run(() -> redis.delete(RedisKeys.rideOffer(rideId)));
        } else if (job != null && job.getStatus() == MatchJobStatus.ASSIGNED) {
            job.cancel(now);
        }
        releaseDriverOf(rideId, now);
    }

    /** RIDE_COMPLETED: the driver is free again. Idempotent. */
    @Transactional
    public void onRideCompleted(UUID rideId) {
        releaseDriverOf(rideId, clock.instant());
    }

    private void releaseDriverOf(UUID rideId, Instant now) {
        drivers.findByCurrentRideId(rideId).ifPresent(driver -> {
            if (driver.endTrip(rideId, now)) {
                AfterCommit.run(() -> presence.setStatus(driver.getId(), DriverStatus.AVAILABLE, driver.getVehicleType()));
            }
        });
    }

    // ------------------------------------------------------------------ the matching step

    /** Snapshot of a job taken in a short transaction, used for the slow search outside it. */
    private record SearchStep(MatchJob job, Set<UUID> alreadyOffered) {
    }

    /** One step for a claimed job: expire a timed-out offer, then search and offer to the best driver. */
    public void process(UUID rideId) {
        SearchStep step = tx.execute(s -> prepare(rideId));
        if (step == null) {
            return;
        }
        MatchJob job = step.job();
        GeoPoint pickup = job.getPickup();
        List<Candidate.Ranked> ranked = ranker.rank(pickup, finder.find(job.getRideType(), pickup, step.alreadyOffered()));
        for (Candidate.Ranked c : ranked) {
            if (!locks.tryLock(c.driverId(), rideId)) {
                continue; // holding an offer for another ride
            }
            if (!presence.isAvailable(c.driverId())) {
                locks.release(c.driverId(), rideId); // went offline / on a trip since the GEO index was read
                continue;
            }
            Boolean offered = tx.execute(s -> makeOffer(rideId, c));
            if (!Boolean.TRUE.equals(offered)) {
                locks.release(c.driverId(), rideId); // job was cancelled meanwhile
            }
            return;
        }
        tx.executeWithoutResult(s -> nobodyFound(rideId));
    }

    private SearchStep prepare(UUID rideId) {
        MatchJob job = jobs.findById(rideId).orElse(null);
        if (job == null || !job.getStatus().isActive()) {
            return null;
        }
        Instant now = clock.instant();
        if (job.getStatus() == MatchJobStatus.OFFERED) {
            Optional<MatchOffer> pending = offers.findByRideIdAndDriverIdAndOutcome(rideId, job.getCurrentDriverId(), Outcome.PENDING);
            if (pending.isPresent() && !pending.get().isExpiredAt(now)) {
                job.retryAt(pending.get().getExpiresAt(), now); // claimed early; look again at expiry
                return null;
            }
            pending.ifPresent(o -> o.close(Outcome.EXPIRED, now));
            UUID driverId = job.getCurrentDriverId();
            job.offerEnded(now.plus(props.lease()), now); // this caller searches next, keep the lease
            AfterCommit.run(() -> endOfferSideEffects(rideId, driverId));
            log.info("Offer for ride {} to driver {} expired", rideId, driverId);
        }
        if (job.getAttempts() >= props.maxAttempts()) {
            fail(job, "No driver accepted the ride after " + job.getAttempts() + " offers", now);
            return null;
        }
        if (!now.isBefore(job.getSearchDeadline())) {
            fail(job, "No drivers available nearby", now);
            return null;
        }
        return new SearchStep(job, new HashSet<>(offers.driverIdsOffered(rideId)));
    }

    private boolean makeOffer(UUID rideId, Candidate.Ranked c) {
        MatchJob job = jobs.findById(rideId).orElse(null);
        if (job == null || job.getStatus() != MatchJobStatus.SEARCHING) {
            return false;
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(props.offerTimeout());
        MatchOffer offer = offers.save(MatchOffer.pending(rideId, c.driverId(), job.getAttempts() + 1, c.etaS(),
                c.distanceM(), now, expiresAt));
        job.offer(c.driverId(), now, expiresAt);
        AfterCommit.run(() -> publishOffer(offer));
        log.info("Ride {} offered to driver {} (attempt {}, {} m away, ETA {} s{})", rideId, c.driverId(),
                offer.getAttempt(), c.distanceM(), c.etaS(), c.etaEstimated() ? ", estimated" : "");
        return true;
    }

    private void nobodyFound(UUID rideId) {
        MatchJob job = jobs.findById(rideId).orElse(null);
        if (job == null || job.getStatus() != MatchJobStatus.SEARCHING) {
            return;
        }
        Instant now = clock.instant();
        Instant next = now.plus(props.retryInterval());
        if (next.isAfter(job.getSearchDeadline())) {
            fail(job, "No drivers available nearby", now);
        } else {
            job.retryAt(next, now);
        }
    }

    private void fail(MatchJob job, String reason, Instant now) {
        job.fail(now);
        outbox.append(EventEnvelope.of(EventType.NO_DRIVER_FOUND, job.getRideId().toString(),
                job.toPayload(null, reason), clock));
        log.info("No driver found for ride {}: {}", job.getRideId(), reason);
    }

    // ------------------------------------------------------------------ driver responses

    @Transactional(readOnly = true)
    public Optional<OfferView> currentOffer(AuthUser driver) {
        requireDriver(driver);
        Instant now = clock.instant();
        return offers.findFirstByDriverIdAndOutcomeOrderByOfferedAtDesc(driver.id(), Outcome.PENDING)
                .filter(o -> !o.isExpiredAt(now))
                .flatMap(o -> jobs.findById(o.getRideId()).map(j -> OfferView.of(o, j, now)));
    }

    @Transactional
    public AssignmentView accept(AuthUser user, UUID rideId) {
        requireDriver(user);
        Instant now = clock.instant();
        MatchOffer offer = pendingOffer(rideId, user.id());
        if (offer.isExpiredAt(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "Offer expired", "This offer has expired");
        }
        MatchJob job = jobs.findById(rideId).orElseThrow(() -> ApiException.notFound("Offer"));
        if (job.getStatus() != MatchJobStatus.OFFERED || !user.id().equals(job.getCurrentDriverId())) {
            throw ApiException.conflict("This ride is no longer offered to you");
        }
        Driver driver = drivers.findById(user.id()).orElseThrow(() -> ApiException.notFound("Driver profile"));
        if (driver.getStatus() != DriverStatus.AVAILABLE) {
            throw ApiException.conflict("You are " + driver.getStatus() + "; go online to accept rides");
        }
        offer.close(Outcome.ACCEPTED, now);
        job.assign(driver.getId(), now);
        driver.startTrip(rideId, now);
        outbox.append(EventEnvelope.of(EventType.DRIVER_ASSIGNED, rideId.toString(),
                job.toPayload(driver.getId(), null), clock));
        // surface optimistic-lock conflicts (e.g. the offer expiring right now) as 409 inside this call
        jobs.flush();
        AfterCommit.run(() -> {
            presence.setStatus(driver.getId(), DriverStatus.ON_TRIP, driver.getVehicleType());
            endOfferSideEffects(rideId, driver.getId());
        });
        log.info("Ride {} assigned to driver {}", rideId, driver.getId());
        return new AssignmentView(rideId, driver.getId(), job.getRiderId(), job.getPickup(), job.getDrop(),
                job.getEstFare(), now);
    }

    @Transactional
    public void reject(AuthUser user, UUID rideId) {
        requireDriver(user);
        closeAsRejected(pendingOffer(rideId, user.id()), clock.instant());
        jobs.flush();
    }

    /** Called when a driver goes offline: a pending offer counts as rejected. Joins the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void withdrawPendingOffer(UUID driverId) {
        offers.findFirstByDriverIdAndOutcomeOrderByOfferedAtDesc(driverId, Outcome.PENDING)
                .ifPresent(o -> closeAsRejected(o, clock.instant()));
    }

    private void closeAsRejected(MatchOffer offer, Instant now) {
        offer.close(Outcome.REJECTED, now);
        UUID rideId = offer.getRideId();
        UUID driverId = offer.getDriverId();
        jobs.findById(rideId)
                .filter(j -> j.getStatus() == MatchJobStatus.OFFERED && driverId.equals(j.getCurrentDriverId()))
                .ifPresent(j -> j.offerEnded(now, now)); // due now: the scheduler offers to the next driver
        AfterCommit.run(() -> endOfferSideEffects(rideId, driverId));
        log.info("Driver {} rejected ride {}", driverId, rideId);
    }

    private MatchOffer pendingOffer(UUID rideId, UUID driverId) {
        return offers.findByRideIdAndDriverIdAndOutcome(rideId, driverId, Outcome.PENDING)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Not found", "No pending offer for this ride"));
    }

    // ------------------------------------------------------------------ admin

    @Transactional(readOnly = true)
    public Optional<JobView> job(UUID rideId) {
        return jobs.findById(rideId).map(j -> JobView.of(j, offers.findByRideIdOrderByAttempt(rideId)));
    }

    // ------------------------------------------------------------------ Redis side effects

    /** {@code ride:{id}:offer}: the live offer, for the tracking service and the rider's "finding a driver" screen. */
    private void publishOffer(MatchOffer offer) {
        String key = RedisKeys.rideOffer(offer.getRideId());
        redis.opsForHash().putAll(key, Map.of(
                "driverId", offer.getDriverId().toString(),
                "attempt", Integer.toString(offer.getAttempt()),
                "expiresAt", offer.getExpiresAt().toString()));
        redis.expire(key, props.offerTimeout());
    }

    private void endOfferSideEffects(UUID rideId, UUID driverId) {
        redis.delete(RedisKeys.rideOffer(rideId));
        if (driverId != null) {
            locks.release(driverId, rideId);
        }
    }

    private static void requireDriver(AuthUser user) {
        if (!user.isDriver()) {
            throw ApiException.forbidden("Only drivers can do this");
        }
    }
}
