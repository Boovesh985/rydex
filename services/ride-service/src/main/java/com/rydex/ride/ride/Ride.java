package com.rydex.ride.ride;

import com.rydex.common.RideType;
import com.rydex.common.geo.GeoPoint;
import com.rydex.events.PaymentPayload;
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

/**
 * Ride aggregate. All status changes go through {@link #transitionTo}, which enforces the
 * state machine in {@link RideStatus}. {@code version} gives optimistic locking so two
 * concurrent updates (e.g. rider cancel vs. driver arrive) cannot both win.
 */
@Entity
@Table(name = "rides")
public class Ride {

    @Id
    private UUID id;

    @Column(name = "rider_id", nullable = false)
    private UUID riderId;

    @Column(name = "driver_id")
    private UUID driverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ride_type", nullable = false)
    private RideType rideType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RideStatus status;

    @Column(name = "pickup_lat", nullable = false)
    private double pickupLat;

    @Column(name = "pickup_lng", nullable = false)
    private double pickupLng;

    @Column(name = "drop_lat", nullable = false)
    private double dropLat;

    @Column(name = "drop_lng", nullable = false)
    private double dropLng;

    @Column(name = "pickup_address")
    private String pickupAddress;

    @Column(name = "drop_address")
    private String dropAddress;

    @Column(name = "est_fare", nullable = false)
    private BigDecimal estFare;

    @Column(name = "final_fare")
    private BigDecimal finalFare;

    @Column(name = "est_distance_m", nullable = false)
    private int estDistanceM;

    @Column(name = "est_duration_s", nullable = false)
    private int estDurationS;

    @Column(name = "surge_multiplier", nullable = false)
    private BigDecimal surgeMultiplier;

    @Column(name = "otp_hash", nullable = false)
    private String otpHash;

    @Column(name = "suggestion_id")
    private UUID suggestionId;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "arrived_at")
    private Instant arrivedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "cancelled_by")
    private String cancelledBy;

    /** INR charged for cancelling after the driver arrived; null when the cancellation was free. */
    @Column(name = "cancellation_fee")
    private BigDecimal cancellationFee;

    /** Mirrors payment-service (payment.events): null until a payment exists for the ride. */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status")
    private PaymentPayload.Status paymentStatus;

    @Column(name = "refunded_amount")
    private BigDecimal refundedAmount;

    /** Booked by an automated (NPC) rider (ADR 0016). */
    @Column(nullable = false)
    private boolean simulated;

    /** Wrapper type: null marks a new entity, so save() persists without a SELECT. */
    @Version
    private Long version;

    protected Ride() {
    }

    public static Ride request(UUID id, UUID riderId, RideType rideType, GeoPoint pickup, GeoPoint drop, Quote quote,
                              String otpHash, UUID suggestionId, Instant now) {
        var r = new Ride();
        r.id = id;
        r.riderId = riderId;
        r.rideType = rideType;
        r.status = RideStatus.REQUESTED;
        r.pickupLat = pickup.lat();
        r.pickupLng = pickup.lng();
        r.pickupAddress = pickup.address();
        r.dropLat = drop.lat();
        r.dropLng = drop.lng();
        r.dropAddress = drop.address();
        r.estFare = quote.fare();
        r.estDistanceM = quote.distanceM();
        r.estDurationS = quote.durationS();
        r.surgeMultiplier = quote.surge();
        r.otpHash = otpHash;
        r.suggestionId = suggestionId;
        r.requestedAt = now;
        return r;
    }

    public void assignDriver(UUID driverId, Instant now) {
        transitionTo(RideStatus.DRIVER_ASSIGNED);
        this.driverId = driverId;
        this.assignedAt = now;
    }

    public void markDriverArrived(Instant now) {
        transitionTo(RideStatus.DRIVER_ARRIVED);
        this.arrivedAt = now;
    }

    public void start(Instant now) {
        transitionTo(RideStatus.IN_PROGRESS);
        this.startedAt = now;
    }

    /** Upfront pricing: the rider pays the quoted fare. */
    public void complete(Instant now) {
        transitionTo(RideStatus.COMPLETED);
        this.completedAt = now;
        this.finalFare = estFare;
    }

    public void cancel(String by, String reason, Instant now) {
        cancel(by, reason, null, now);
    }

    /** {@code fee}: what the rider pays for cancelling late, or null. */
    public void cancel(String by, String reason, BigDecimal fee, Instant now) {
        transitionTo(RideStatus.CANCELLED);
        this.cancelledAt = now;
        this.cancelledBy = by;
        this.cancelReason = reason;
        this.cancellationFee = fee;
    }

    /** payment-service reported a change to the ride's payment. */
    public void paymentChanged(PaymentPayload.Status status, BigDecimal refunded) {
        this.paymentStatus = status;
        this.refundedAmount = refunded;
    }

    public void markNoDriverFound(Instant now) {
        transitionTo(RideStatus.NO_DRIVER_FOUND);
        this.cancelledAt = now;
        this.cancelReason = "No driver available";
    }

    private void transitionTo(RideStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalRideTransitionException(status, next);
        }
        status = next;
    }

    public GeoPoint pickup() {
        return new GeoPoint(pickupLat, pickupLng, pickupAddress);
    }

    public GeoPoint drop() {
        return new GeoPoint(dropLat, dropLng, dropAddress);
    }

    public UUID getId() {
        return id;
    }

    public UUID getRiderId() {
        return riderId;
    }

    public UUID getDriverId() {
        return driverId;
    }

    public RideType getRideType() {
        return rideType;
    }

    public RideStatus getStatus() {
        return status;
    }

    public BigDecimal getEstFare() {
        return estFare;
    }

    public BigDecimal getFinalFare() {
        return finalFare;
    }

    public int getEstDistanceM() {
        return estDistanceM;
    }

    public int getEstDurationS() {
        return estDurationS;
    }

    public BigDecimal getSurgeMultiplier() {
        return surgeMultiplier;
    }

    public String getOtpHash() {
        return otpHash;
    }

    public UUID getSuggestionId() {
        return suggestionId;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public Instant getArrivedAt() {
        return arrivedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public String getCancelledBy() {
        return cancelledBy;
    }

    public BigDecimal getCancellationFee() {
        return cancellationFee;
    }

    public PaymentPayload.Status getPaymentStatus() {
        return paymentStatus;
    }

    public BigDecimal getRefundedAmount() {
        return refundedAmount;
    }

    public boolean isSimulated() {
        return simulated;
    }

    /** At booking: the rider is an NPC the simulator plays. */
    public void markSimulated() {
        this.simulated = true;
    }
}
