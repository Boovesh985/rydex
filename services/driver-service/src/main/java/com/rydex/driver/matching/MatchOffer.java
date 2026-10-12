package com.rydex.driver.matching;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One offer of a ride to one driver. */
@Entity
@Table(name = "match_offers")
public class MatchOffer {

    public enum Outcome {
        PENDING,
        ACCEPTED,
        REJECTED,
        EXPIRED,
        CANCELLED
    }

    @Id
    private UUID id;

    @Column(name = "ride_id", nullable = false)
    private UUID rideId;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "eta_s", nullable = false)
    private int etaS;

    @Column(name = "distance_m", nullable = false)
    private int distanceM;

    @Column(name = "offered_at", nullable = false)
    private Instant offeredAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Outcome outcome;

    protected MatchOffer() {
    }

    static MatchOffer pending(UUID rideId, UUID driverId, int attempt, int etaS, int distanceM,
                              Instant now, Instant expiresAt) {
        MatchOffer o = new MatchOffer();
        o.id = UUID.randomUUID();
        o.rideId = rideId;
        o.driverId = driverId;
        o.attempt = attempt;
        o.etaS = etaS;
        o.distanceM = distanceM;
        o.offeredAt = now;
        o.expiresAt = expiresAt;
        o.outcome = Outcome.PENDING;
        return o;
    }

    void close(Outcome result, Instant now) {
        if (outcome != Outcome.PENDING) {
            throw new IllegalStateException("offer already " + outcome);
        }
        outcome = result;
        respondedAt = now;
    }

    boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public UUID getRideId() {
        return rideId;
    }

    public UUID getDriverId() {
        return driverId;
    }

    public int getAttempt() {
        return attempt;
    }

    public int getEtaS() {
        return etaS;
    }

    public int getDistanceM() {
        return distanceM;
    }

    public Instant getOfferedAt() {
        return offeredAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public Outcome getOutcome() {
        return outcome;
    }
}
