package com.rydex.driver.driver;

import com.rydex.common.DriverStatus;
import com.rydex.common.RideType;
import com.rydex.presence.DriverLocation;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class DriverDtos {

    private DriverDtos() {
    }

    public record UpsertProfileRequest(
            @NotNull RideType vehicleType,
            @NotBlank @Size(max = 20) @Pattern(regexp = "[A-Za-z0-9 -]+", message = "letters, digits, spaces and dashes only")
            String vehicleNumber,
            @NotBlank @Size(max = 60) String vehicleModel) {
    }

    /** {@code lat}/{@code lng} are optional; when given they count as the first heartbeat. */
    public record StatusRequest(
            @NotNull Availability status,
            @DecimalMin("-90") @DecimalMax("90") Double lat,
            @DecimalMin("-180") @DecimalMax("180") Double lng) {
    }

    /** What a driver may choose; ON_TRIP is set by matching only. */
    public enum Availability {
        AVAILABLE,
        OFFLINE
    }

    public record LocationRequest(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
            @DecimalMin("0") @DecimalMax("360") Double heading,
            @DecimalMin("0") @DecimalMax("100") Double speed) {
    }

    public record DriverView(
            UUID id,
            String name,
            RideType vehicleType,
            String vehicleNumber,
            String vehicleModel,
            BigDecimal rating,
            DriverStatus status,
            UUID currentRideId,
            boolean searchable,
            Position lastLocation,
            Instant updatedAt) {

        static DriverView of(Driver d, boolean searchable, DriverLocation loc) {
            return new DriverView(d.getId(), d.getName(), d.getVehicleType(), d.getVehicleNumber(),
                    d.getVehicleModel(), d.getRating(), d.getStatus(), d.getCurrentRideId(), searchable,
                    loc == null ? null : new Position(loc.lat(), loc.lng(), loc.at()), d.getUpdatedAt());
        }
    }

    public record Position(double lat, double lng, Instant at) {
    }
}
