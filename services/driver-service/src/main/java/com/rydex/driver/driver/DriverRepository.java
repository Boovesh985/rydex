package com.rydex.driver.driver;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DriverRepository extends JpaRepository<Driver, UUID> {

    @Query("select d from Driver d where d.currentRideId = :rideId")
    Optional<Driver> findByCurrentRideId(UUID rideId);

    boolean existsByVehicleNumberIgnoreCaseAndIdNot(String vehicleNumber, UUID id);
}
