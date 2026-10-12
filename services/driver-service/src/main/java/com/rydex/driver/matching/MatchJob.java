package com.rydex.driver.matching;

import com.rydex.common.RideType;
import com.rydex.common.geo.GeoPoint;
import com.rydex.events.RidePayload;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Matching state for one requested ride. {@code dueAt} is when the scheduler must look at
 * the job next: when the current offer expires, or when to search again.
 */
@Entity
@Table(name = "match_jobs")
public class MatchJob {

    @Id
    @Column(name = "ride_id")
    private UUID rideId;

    @Column(name = "rider_id", nullable = false)
    private UUID riderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ride_type", nullable = false)
    private RideType rideType;

    @Column(name = "pickup_lat", nullable = false)
    private double pickupLat;

    @Column(name = "pickup_lng", nullable = false)
    private double pickupLng;

    @Column(name = "pickup_address")
    private String pickupAddress;

    @Column(name = "drop_lat", nullable = false)
    private double dropLat;

    @Column(name = "drop_lng", nullable = false)
    private double dropLng;

    @Column(name = "drop_address")
    private String dropAddress;

    @Column(name = "est_fare", nullable = false)
    private BigDecimal estFare;

    @Column(name = "est_distance_m", nullable = false)
    private int estDistanceM;

    @Column(name = "est_duration_s", nullable = false)
    private int estDurationS;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MatchJobStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "current_driver_id")
    private UUID currentDriverId;

    @Column(name = "assigned_driver_id")
    private UUID assignedDriverId;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "search_deadline", nullable = false)
    private Instant searchDeadline;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected MatchJob() {
    }

    /** A new job is due immediately, so the scheduler starts searching on its next tick. */
    public static MatchJob create(UUID rideId, RidePayload ride, Instant now, Duration searchTimeout) {
        MatchJob j = new MatchJob();
        j.rideId = rideId;
        j.riderId = ride.riderId();
        j.rideType = ride.rideType();
        j.pickupLat = ride.pickup().lat();
        j.pickupLng = ride.pickup().lng();
        j.pickupAddress = ride.pickup().address();
        j.dropLat = ride.drop().lat();
        j.dropLng = ride.drop().lng();
        j.dropAddress = ride.drop().address();
        j.estFare = ride.fare();
        j.estDistanceM = ride.estDistanceM() == null ? 0 : ride.estDistanceM();
        j.estDurationS = ride.estDurationS() == null ? 0 : ride.estDurationS();
        j.status = MatchJobStatus.SEARCHING;
        j.dueAt = now;
        j.searchDeadline = now.plus(searchTimeout);
        j.createdAt = now;
        j.updatedAt = now;
        return j;
    }

    void offer(UUID driverId, Instant now, Instant expiresAt) {
        require(MatchJobStatus.SEARCHING);
        status = MatchJobStatus.OFFERED;
        currentDriverId = driverId;
        attempts++;
        dueAt = expiresAt;
        updatedAt = now;
    }

    /** The current offer was rejected or expired: search again when {@code nextSearchAt} comes. */
    void offerEnded(Instant nextSearchAt, Instant now) {
        require(MatchJobStatus.OFFERED);
        status = MatchJobStatus.SEARCHING;
        currentDriverId = null;
        dueAt = nextSearchAt;
        updatedAt = now;
    }

    void retryAt(Instant at, Instant now) {
        require(MatchJobStatus.SEARCHING);
        dueAt = at;
        updatedAt = now;
    }

    void assign(UUID driverId, Instant now) {
        require(MatchJobStatus.OFFERED);
        status = MatchJobStatus.ASSIGNED;
        assignedDriverId = driverId;
        currentDriverId = null;
        dueAt = null;
        updatedAt = now;
    }

    void fail(Instant now) {
        require(MatchJobStatus.SEARCHING);
        status = MatchJobStatus.NO_DRIVER_FOUND;
        dueAt = null;
        updatedAt = now;
    }

    void cancel(Instant now) {
        status = MatchJobStatus.CANCELLED;
        currentDriverId = null;
        dueAt = null;
        updatedAt = now;
    }

    private void require(MatchJobStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("match job " + rideId + " is " + status + ", expected " + expected);
        }
    }

    /** The ride as it travels in events, with the given driver (null if none). */
    RidePayload toPayload(UUID driverId, String reason) {
        return new RidePayload(riderId, driverId, rideType, getPickup(), getDrop(), estFare,
                estDistanceM, estDurationS, reason);
    }

    public GeoPoint getPickup() {
        return new GeoPoint(pickupLat, pickupLng, pickupAddress);
    }

    public GeoPoint getDrop() {
        return new GeoPoint(dropLat, dropLng, dropAddress);
    }

    public UUID getRideId() {
        return rideId;
    }

    public UUID getRiderId() {
        return riderId;
    }

    public RideType getRideType() {
        return rideType;
    }

    public BigDecimal getEstFare() {
        return estFare;
    }

    public int getEstDistanceM() {
        return estDistanceM;
    }

    public int getEstDurationS() {
        return estDurationS;
    }

    public MatchJobStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public UUID getCurrentDriverId() {
        return currentDriverId;
    }

    public UUID getAssignedDriverId() {
        return assignedDriverId;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public Instant getSearchDeadline() {
        return searchDeadline;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
