package com.rydex.driver.driver;

import com.rydex.common.DriverStatus;
import com.rydex.common.RideType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A driver's profile and availability. The id is the DRIVER user's id in ride-service. */
@Entity
@Table(name = "drivers")
public class Driver {

    @Id
    @Column(name = "user_id")
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false)
    private RideType vehicleType;

    @Column(name = "vehicle_number", nullable = false)
    private String vehicleNumber;

    @Column(name = "vehicle_model", nullable = false)
    private String vehicleModel;

    @Column(nullable = false, precision = 3, scale = 2)
    private BigDecimal rating;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DriverStatus status;

    @Column(name = "current_ride_id")
    private UUID currentRideId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Driver() {
    }

    public static Driver register(UUID id, String name, RideType type, String number, String model, Instant now) {
        Driver d = new Driver();
        d.id = id;
        d.name = name;
        d.vehicleType = type;
        d.vehicleNumber = number;
        d.vehicleModel = model;
        d.rating = new BigDecimal("5.00");
        d.status = DriverStatus.OFFLINE;
        d.createdAt = now;
        d.updatedAt = now;
        return d;
    }

    /** Vehicle details can change only while offline, so the GEO index never holds a stale ride type. */
    public void updateVehicle(String name, RideType type, String number, String model, Instant now) {
        if (status != DriverStatus.OFFLINE && type != vehicleType) {
            throw new IllegalStateException("Go offline before changing the vehicle type");
        }
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
        this.vehicleType = type;
        this.vehicleNumber = number;
        this.vehicleModel = model;
        this.updatedAt = now;
    }

    /** Driver-initiated toggle between OFFLINE and AVAILABLE. */
    public void setAvailability(boolean available, Instant now) {
        if (status == DriverStatus.ON_TRIP) {
            throw new IllegalStateException("Finish the current trip first");
        }
        status = available ? DriverStatus.AVAILABLE : DriverStatus.OFFLINE;
        updatedAt = now;
    }

    public void startTrip(UUID rideId, Instant now) {
        if (status != DriverStatus.AVAILABLE) {
            throw new IllegalStateException("Driver is " + status + ", not AVAILABLE");
        }
        status = DriverStatus.ON_TRIP;
        currentRideId = rideId;
        updatedAt = now;
    }

    /** Back to AVAILABLE after the ride ends. Returns false if the driver was not on that ride. */
    public boolean endTrip(UUID rideId, Instant now) {
        if (status != DriverStatus.ON_TRIP || !rideId.equals(currentRideId)) {
            return false;
        }
        status = DriverStatus.AVAILABLE;
        currentRideId = null;
        updatedAt = now;
        return true;
    }

    /**
     * Admin recovery for a driver stuck ON_TRIP because the end of their ride never reached this
     * service. They go OFFLINE rather than AVAILABLE, so they are offered nothing until they choose
     * to go online again. Returns the ride they were released from.
     */
    public UUID release(Instant now) {
        if (status != DriverStatus.ON_TRIP) {
            throw new IllegalStateException("Driver is " + status + ", not on a trip");
        }
        UUID rideId = currentRideId;
        status = DriverStatus.OFFLINE;
        currentRideId = null;
        updatedAt = now;
        return rideId;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public RideType getVehicleType() {
        return vehicleType;
    }

    public String getVehicleNumber() {
        return vehicleNumber;
    }

    public String getVehicleModel() {
        return vehicleModel;
    }

    public BigDecimal getRating() {
        return rating;
    }

    public DriverStatus getStatus() {
        return status;
    }

    public UUID getCurrentRideId() {
        return currentRideId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
