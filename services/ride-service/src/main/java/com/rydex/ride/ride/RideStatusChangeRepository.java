package com.rydex.ride.ride;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RideStatusChangeRepository extends JpaRepository<RideStatusChange, Long> {

    List<RideStatusChange> findByRideIdOrderByAtAscIdAsc(UUID rideId);
}
