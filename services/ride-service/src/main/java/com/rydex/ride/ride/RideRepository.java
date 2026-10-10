package com.rydex.ride.ride;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RideRepository extends JpaRepository<Ride, UUID> {

    Page<Ride> findByRiderIdOrderByRequestedAtDesc(UUID riderId, Pageable pageable);

    Page<Ride> findByDriverIdOrderByRequestedAtDesc(UUID driverId, Pageable pageable);

    Page<Ride> findAllByOrderByRequestedAtDesc(Pageable pageable);
}
