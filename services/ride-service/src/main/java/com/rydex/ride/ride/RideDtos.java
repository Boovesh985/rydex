package com.rydex.ride.ride;

import com.rydex.common.RideType;
import com.rydex.common.geo.GeoPoint;
import com.rydex.events.PaymentPayload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RideDtos {

    private RideDtos() {
    }

    public record LocationInput(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
            @Size(max = 300) String address) {

        public GeoPoint toGeoPoint() {
            return new GeoPoint(lat, lng, address);
        }
    }

    public record EstimateRequest(@Valid @NotNull LocationInput pickup, @Valid @NotNull LocationInput drop) {
    }

    /** One bookable ride type. {@code etaMinutes} is null when no driver of that type is nearby. */
    public record RideOption(RideType rideType, BigDecimal fare, Integer etaMinutes, int driversNearby) {
    }

    public record RouteView(String polyline, String source) {
    }

    public record EstimateResponse(
            GeoPoint pickup,
            GeoPoint drop,
            int distanceM,
            int durationS,
            BigDecimal surgeMultiplier,
            RouteView route,
            List<RideOption> options) {
    }

    public record BookRideRequest(
            @Valid @NotNull LocationInput pickup,
            @Valid @NotNull LocationInput drop,
            @NotNull RideType rideType,
            UUID suggestionId) {
    }

    public record CancelRequest(@Size(max = 300) String reason) {
    }

    public record StartRideRequest(@NotBlank @Pattern(regexp = "\\d{4}", message = "must be 4 digits") String otp) {
    }

    public record StatusChangeView(RideStatus from, RideStatus to, Instant at) {
    }

    /**
     * @param otp             shown to the rider only, until the trip starts
     * @param cancellationFee INR charged for cancelling after the driver arrived
     * @param paymentStatus   the ride's payment (fare or cancellation fee), once payment-service has one
     * @param timeline        status history; only populated on single-ride reads
     * @param simulated       booked by an automated (NPC) rider of the living city (ADR 0016)
     */
    public record RideView(
            UUID id,
            RideStatus status,
            RideType rideType,
            UUID riderId,
            UUID driverId,
            GeoPoint pickup,
            GeoPoint drop,
            BigDecimal estFare,
            BigDecimal finalFare,
            int estDistanceM,
            int estDurationS,
            BigDecimal surgeMultiplier,
            String otp,
            Instant requestedAt,
            Instant assignedAt,
            Instant arrivedAt,
            Instant startedAt,
            Instant completedAt,
            Instant cancelledAt,
            String cancelReason,
            String cancelledBy,
            BigDecimal cancellationFee,
            PaymentPayload.Status paymentStatus,
            BigDecimal refundedAmount,
            List<StatusChangeView> timeline,
            boolean simulated) {

        static RideView of(Ride r, String otp, List<StatusChangeView> timeline) {
            return new RideView(r.getId(), r.getStatus(), r.getRideType(), r.getRiderId(), r.getDriverId(),
                    r.pickup(), r.drop(), r.getEstFare(), r.getFinalFare(), r.getEstDistanceM(), r.getEstDurationS(),
                    r.getSurgeMultiplier(), otp, r.getRequestedAt(), r.getAssignedAt(), r.getArrivedAt(),
                    r.getStartedAt(), r.getCompletedAt(), r.getCancelledAt(), r.getCancelReason(), r.getCancelledBy(),
                    r.getCancellationFee(), r.getPaymentStatus(), r.getRefundedAmount(), timeline, r.isSimulated());
        }
    }

    public record PageView<T>(List<T> items, int page, int size, long total) {
    }
}
