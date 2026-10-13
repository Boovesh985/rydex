package com.rydex.tracking.ride;

import com.rydex.common.RideType;
import com.rydex.common.geo.GeoPoint;
import com.rydex.events.EventType;
import com.rydex.events.RidePayload;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * tracking-service's view of a ride, built from {@code ride.events}: who may watch it, which
 * driver is on it, and where it is headed. Transitions mirror ride-service's state machine, so
 * the projection ignores the same out-of-date events ride-service does (e.g. an assignment that
 * arrives after the rider cancelled).
 */
@Entity
@Table(name = "tracked_rides")
public class TrackedRide {

    public enum Status {
        REQUESTED,
        DRIVER_ASSIGNED,
        DRIVER_ARRIVED,
        IN_PROGRESS,
        COMPLETED,
        CANCELLED,
        NO_DRIVER_FOUND;

        /** The driver is on this ride: their location is broadcast to the rider. */
        public static final Set<Status> ACTIVE = EnumSet.of(DRIVER_ASSIGNED, DRIVER_ARRIVED, IN_PROGRESS);

        public boolean isTerminal() {
            return this == COMPLETED || this == CANCELLED || this == NO_DRIVER_FOUND;
        }
    }

    @Id
    @Column(name = "ride_id")
    private UUID id;

    @Column(name = "rider_id", nullable = false)
    private UUID riderId;

    @Column(name = "driver_id")
    private UUID driverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ride_type")
    private RideType rideType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "pickup_lat", nullable = false)
    private double pickupLat;

    @Column(name = "pickup_lng", nullable = false)
    private double pickupLng;

    @Column(name = "drop_lat", nullable = false)
    private double dropLat;

    @Column(name = "drop_lng", nullable = false)
    private double dropLng;

    @Column
    private BigDecimal fare;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TrackedRide() {
    }

    /** First sight of a ride, from whichever of its events arrives first (all carry the ride's details). */
    static TrackedRide requested(UUID rideId, RidePayload p, Instant now) {
        TrackedRide r = new TrackedRide();
        r.id = rideId;
        r.riderId = p.riderId();
        r.rideType = p.rideType();
        r.status = Status.REQUESTED;
        r.pickupLat = p.pickup().lat();
        r.pickupLng = p.pickup().lng();
        r.dropLat = p.drop().lat();
        r.dropLng = p.drop().lng();
        r.fare = p.fare();
        r.updatedAt = now;
        return r;
    }

    /**
     * Applies one event. Returns false when it does not change the ride (a duplicate step, or an
     * out-of-date event after the ride moved on), in which case nobody is notified.
     */
    boolean apply(EventType type, RidePayload p, Instant now) {
        Status next = switch (type) {
            case RIDE_REQUESTED -> null;
            case DRIVER_ASSIGNED -> status == Status.REQUESTED ? Status.DRIVER_ASSIGNED : null;
            case NO_DRIVER_FOUND -> status == Status.REQUESTED ? Status.NO_DRIVER_FOUND : null;
            case DRIVER_ARRIVED -> status == Status.DRIVER_ASSIGNED ? Status.DRIVER_ARRIVED : null;
            case RIDE_STARTED -> status == Status.DRIVER_ARRIVED ? Status.IN_PROGRESS : null;
            case RIDE_COMPLETED -> status == Status.IN_PROGRESS ? Status.COMPLETED : null;
            case RIDE_CANCELLED -> status.isTerminal() || status == Status.IN_PROGRESS ? null : Status.CANCELLED;
            default -> null;
        };
        if (next == null) {
            return false;
        }
        if (type == EventType.DRIVER_ASSIGNED) {
            driverId = p.driverId();
        }
        if (p != null && p.fare() != null) {
            fare = p.fare();
        }
        status = next;
        updatedAt = now;
        return true;
    }

    public boolean isActive() {
        return Status.ACTIVE.contains(status);
    }

    public boolean isParticipant(UUID userId) {
        return userId.equals(riderId) || userId.equals(driverId);
    }

    public GeoPoint pickup() {
        return GeoPoint.of(pickupLat, pickupLng);
    }

    public GeoPoint drop() {
        return GeoPoint.of(dropLat, dropLng);
    }

    public UUID getId() {
        return id;
    }

    public UUID getRiderId() {
        return riderId;
    }

    public UUID getDriverId() {
        return driverId;
    }

    public RideType getRideType() {
        return rideType;
    }

    public Status getStatus() {
        return status;
    }

    public BigDecimal getFare() {
        return fare;
    }
}
