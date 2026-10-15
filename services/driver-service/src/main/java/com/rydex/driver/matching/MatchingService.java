package com.rydex.driver.matching;

import com.rydex.common.DriverStatus;
import com.rydex.common.RedisKeys;
import com.rydex.common.geo.GeoPoint;
import com.rydex.driver.driver.Driver;
import com.rydex.driver.driver.DriverRepository;
import com.rydex.driver.matching.MatchOffer.Outcome;
import com.rydex.driver.matching.MatchingDtos.AssignmentView;
import com.rydex.driver.matching.MatchingDtos.JobView;
import com.rydex.driver.matching.MatchingDtos.OfferView;
import com.rydex.driver.security.AuthUser;
import com.rydex.driver.support.AfterCommit;
import com.rydex.driver.web.ApiException;
import com.rydex.events.EventEnvelope;
import com.rydex.events.EventType;
import com.rydex.events.RidePayload;
import com.rydex.messaging.OutboxWriter;
import com.rydex.presence.PresenceStore;
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
    /** How many free drivers near a pickup are looked through for real people (all of a city's NPCs). */
    static final int PEOPLE_FIRST_SCAN = 200;

    private final MatchJobRepository jobs;
    private final MatchOfferRepository offers;
    private final DriverRepository drivers;
    private final CandidateFinder finder;
    private final CandidateRanker ranker;
    private final DriverLocks locks;
    private final PresenceStore presence;
    private final OutboxWriter outbox;
    private final OfferNotifier notifier;
    private final StringRedisTemplate redis;
    private final MatchingProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public MatchingService(MatchJobRepository jobs, MatchOfferRepository offers, DriverRepository drivers,
                           CandidateFinder finder, CandidateRanker ranker, DriverLocks locks, PresenceStore presence,
                           OutboxWriter outbox, OfferNotifier notifier, StringRedisTemplate redis,
                           MatchingProperties props, TransactionTemplate tx, Clock clock) {
        this.jobs = jobs;
        this.offers = offers;
        this.drivers = drivers;
        this.finder = finder;
        this.ranker = ranker;
        this.locks = locks;
        this.presence = presence;
        this.outbox = outbox;
        this.notifier = notifier;
        this.redis = redis;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ ride events

    /**
     * RIDE_REQUESTED: start matching. Idempotent per ride; returns false for a duplicate.
     * {@code requestedAt} is when the rider booked (the event's time): the search deadline counts from it.
     */
    @Transactional
    public boolean onRideRequested(UUID rideId, RidePayload ride, Instant requestedAt) {
        if (jobs.existsById(rideId)) {
            return false;
        }
        jobs.save(MatchJob.create(rideId, ride, requestedAt, clock.instant(), props.searchTimeout(), props.offerTimeout()));
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
                AfterCommit.run(() -> {
                    locks.release(offer.getDriverId(), rideId);
                    notifier.closed(offer.getDriverId(), rideId, OfferNotifier.Closed.CANCELLED);
                });
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

    /** What happened when offering the ride to one candidate. */
    private enum OfferAttempt {
        OFFERED,
        /** Locked by another ride's offer, or not actually available: try the next candidate. */
        DRIVER_BUSY,
        /** The job was cancelled or moved on meanwhile: stop. */
        JOB_CLOSED
    }

    /**
     * One step for a claimed job: expire a timed-out offer, then search and offer to the best driver.
     * The radius widens (3, 5, 8 km) not only when nobody is inside it but also when everybody inside
     * is busy, e.g. holding another ride's offer, so free drivers further out are still reached.
     */
    public void process(UUID rideId) {
        SearchStep step = tx.execute(s -> prepare(rideId));
        if (step == null) {
            return;
        }
        MatchJob job = step.job();
        GeoPoint pickup = job.getPickup();
        Set<UUID> tried = new HashSet<>(step.alreadyOffered());
        if (offerToPeopleFirst(job, tried) != OfferAttempt.DRIVER_BUSY) {
            return;
        }
        for (double radiusKm : props.searchRadiiKm()) {
            List<Candidate> found = finder.within(job.getRideType(), pickup, radiusKm, tried);
            for (Candidate.Ranked c : ranker.rank(pickup, found)) {
                tried.add(c.driverId());
                if (tryOffer(rideId, c) != OfferAttempt.DRIVER_BUSY) {
                    return;
                }
            }
        }
        tx.executeWithoutResult(s -> nobodyFound(rideId));
    }

    /**
     * The living city (ADR 0016): dozens of NPC drivers would win nearly every ride, so a real person
     * online nearby is offered it first: anywhere in the search area for a real rider's ride (the
     * owner testing both sides), within the first radius for an NPC's. Once they have been offered
     * the ride, normal matching carries on. Without NPCs around, this changes nothing.
     */
    private OfferAttempt offerToPeopleFirst(MatchJob job, Set<UUID> tried) {
        List<Double> radii = props.searchRadiiKm();
        double reach = job.isSimulatedRider() ? radii.getFirst() : radii.getLast();
        List<Candidate> found = finder.within(job.getRideType(), job.getPickup(), reach, tried, PEOPLE_FIRST_SCAN);
        Set<UUID> npcs = new HashSet<>();
        drivers.findAllById(found.stream().map(Candidate::driverId).toList()).forEach(d -> {
            if (d.isSimulated()) {
                npcs.add(d.getId());
            }
        });
        if (npcs.isEmpty()) {
            return OfferAttempt.DRIVER_BUSY;
        }
        List<Candidate> people = found.stream().filter(c -> !npcs.contains(c.driverId())).toList();
        for (Candidate.Ranked c : ranker.rank(job.getPickup(), people)) {
            tried.add(c.driverId());
            OfferAttempt result = tryOffer(job.getRideId(), c);
            if (result != OfferAttempt.DRIVER_BUSY) {
                return result;
            }
        }
        return OfferAttempt.DRIVER_BUSY;
    }

    /** Locks the driver, then records the offer; the lock is kept only if the offer was made. */
    private OfferAttempt tryOffer(UUID rideId, Candidate.Ranked c) {
        if (!locks.tryLock(c.driverId(), rideId)) {
            return OfferAttempt.DRIVER_BUSY; // holding an offer for another ride
        }
        OfferAttempt result = OfferAttempt.DRIVER_BUSY;
        try {
            if (!presence.isAvailable(c.driverId())) {
                return result; // went offline / on a trip since the GEO index was read
            }
            result = tx.execute(s -> makeOffer(rideId, c));
            return result;
        } finally {
            // also on failure: a lock left behind would hide the driver from every ride until it expired
            if (result != OfferAttempt.OFFERED) {
                locks.release(c.driverId(), rideId);
            }
        }
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
            AfterCommit.run(() -> endOfferSideEffects(rideId, driverId, OfferNotifier.Closed.EXPIRED));
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

    private OfferAttempt makeOffer(UUID rideId, Candidate.Ranked c) {
        MatchJob job = jobs.findById(rideId).orElse(null);
        if (job == null || job.getStatus() != MatchJobStatus.SEARCHING) {
            return OfferAttempt.JOB_CLOSED;
        }
        // Redis is a projection that a failed after-commit write can leave stale; Postgres decides
        Driver driver = drivers.findById(c.driverId()).orElse(null);
        if (driver == null || driver.getStatus() != DriverStatus.AVAILABLE || driver.getVehicleType() != job.getRideType()) {
            if (driver != null) {
                AfterCommit.run(() -> presence.setStatus(driver.getId(), driver.getStatus(), driver.getVehicleType()));
                log.warn("Driver {} is {} ({}) in Postgres but was searchable in Redis; projection repaired",
                        driver.getId(), driver.getStatus(), driver.getVehicleType());
            }
            return OfferAttempt.DRIVER_BUSY;
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(props.offerTimeout());
        MatchOffer offer = offers.save(MatchOffer.pending(rideId, c.driverId(), job.getAttempts() + 1, c.etaS(),
                c.distanceM(), now, expiresAt));
        job.offer(c.driverId(), now, expiresAt);
        AfterCommit.run(() -> publishOffer(offer, job));
        log.info("Ride {} offered to driver {} (attempt {}, {} m away, ETA {} s{})", rideId, c.driverId(),
                offer.getAttempt(), c.distanceM(), c.etaS(), c.etaEstimated() ? ", estimated" : "");
        return OfferAttempt.OFFERED;
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
            endOfferSideEffects(rideId, driver.getId(), OfferNotifier.Closed.ACCEPTED);
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
        AfterCommit.run(() -> endOfferSideEffects(rideId, driverId, OfferNotifier.Closed.REJECTED));
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

    /** The driver a ride was assigned to, if the caller may see it: the ride's rider, that driver or an admin. */
    @Transactional(readOnly = true)
    public Optional<UUID> assignedDriver(AuthUser caller, UUID rideId) {
        return jobs.findById(rideId)
                .filter(j -> j.getAssignedDriverId() != null)
                .filter(j -> caller.isAdmin() || caller.id().equals(j.getRiderId())
                        || caller.id().equals(j.getAssignedDriverId()))
                .map(MatchJob::getAssignedDriverId);
    }

    // ------------------------------------------------------------------ Redis side effects

    /**
     * {@code ride:{id}:offer}: the live offer, for the rider's "finding a driver" screen; and the
     * push to the driver's WebSocket.
     */
    private void publishOffer(MatchOffer offer, MatchJob job) {
        String key = RedisKeys.rideOffer(offer.getRideId());
        redis.opsForHash().putAll(key, Map.of(
                "driverId", offer.getDriverId().toString(),
                "attempt", Integer.toString(offer.getAttempt()),
                "expiresAt", offer.getExpiresAt().toString()));
        redis.expire(key, props.offerTimeout());
        notifier.offered(offer.getDriverId(), OfferView.of(offer, job, clock.instant()));
    }

    private void endOfferSideEffects(UUID rideId, UUID driverId, OfferNotifier.Closed reason) {
        redis.delete(RedisKeys.rideOffer(rideId));
        if (driverId != null) {
            locks.release(driverId, rideId);
            notifier.closed(driverId, rideId, reason);
        }
    }

    private static void requireDriver(AuthUser user) {
        if (!user.isDriver()) {
            throw ApiException.forbidden("Only drivers can do this");
        }
    }
}
