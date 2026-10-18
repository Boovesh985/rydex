package com.rydex.ride.ride;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RideRepository extends JpaRepository<Ride, UUID> {

    Page<Ride> findByRiderIdOrderByRequestedAtDesc(UUID riderId, Pageable pageable);

    Page<Ride> findByDriverIdOrderByRequestedAtDesc(UUID driverId, Pageable pageable);

    Page<Ride> findAllByOrderByRequestedAtDesc(Pageable pageable);

    Page<Ride> findByStatusInOrderByRequestedAtDesc(Collection<RideStatus> statuses, Pageable pageable);

    @Query("select r.id from Ride r where r.status = :status and r.requestedAt < :before order by r.requestedAt")
    List<UUID> findIdsByStatusRequestedBefore(RideStatus status, Instant before, Pageable pageable);

    /** Rides in one of {@code statuses} whose last step (assigned, arrived or started) is older than {@code before}. */
    @Query("""
            select r from Ride r where r.status in :statuses
            and coalesce(r.startedAt, r.arrivedAt, r.assignedAt) < :before
            order by coalesce(r.startedAt, r.arrivedAt, r.assignedAt)""")
    List<Ride> findUnderWaySince(Collection<RideStatus> statuses, Instant before, Pageable pageable);
}
