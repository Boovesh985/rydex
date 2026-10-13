package com.rydex.tracking.ride;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrackedRideRepository extends JpaRepository<TrackedRide, UUID> {

    /** The ride a driver is on (assigned, arrived or in progress); at most one. */
    Optional<TrackedRide> findFirstByDriverIdAndStatusIn(UUID driverId, Collection<TrackedRide.Status> statuses);

    default Optional<TrackedRide> activeRideOf(UUID driverId) {
        return findFirstByDriverIdAndStatusIn(driverId, TrackedRide.Status.ACTIVE);
    }
}
