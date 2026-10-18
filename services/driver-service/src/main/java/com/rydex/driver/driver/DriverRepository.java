package com.rydex.driver.driver;

import com.rydex.common.DriverStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DriverRepository extends JpaRepository<Driver, UUID> {

    @Query("select d from Driver d where d.currentRideId = :rideId")
    Optional<Driver> findByCurrentRideId(UUID rideId);

    boolean existsByVehicleNumberIgnoreCaseAndIdNot(String vehicleNumber, UUID id);

    List<Driver> findByStatusIn(Collection<DriverStatus> statuses);

    /**
     * Admin search: the name contains {@code name} (case-insensitive), or the plate contains
     * {@code plate} with spaces ignored (callers pass it upper-cased, without spaces).
     */
    @Query("""
            select d from Driver d
            where lower(d.name) like concat('%', lower(:name), '%')
               or replace(upper(d.vehicleNumber), ' ', '') like concat('%', :plate, '%')
            order by d.name""")
    List<Driver> search(String name, String plate, Pageable page);
}
