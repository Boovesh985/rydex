package com.rydex.ride.ride;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Audit row for every status change of a ride. */
@Entity
@Table(name = "ride_status_history")
public class RideStatusChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ride_id", nullable = false)
    private UUID rideId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private RideStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private RideStatus toStatus;

    @Column(nullable = false)
    private Instant at;

    @Column(nullable = false)
    private String actor;

    protected RideStatusChange() {
    }

    public RideStatusChange(UUID rideId, RideStatus fromStatus, RideStatus toStatus, Instant at, String actor) {
        this.rideId = rideId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.at = at;
        this.actor = actor;
    }

    public RideStatus getFromStatus() {
        return fromStatus;
    }

    public RideStatus getToStatus() {
        return toStatus;
    }

    public Instant getAt() {
        return at;
    }

    public String getActor() {
        return actor;
    }
}
