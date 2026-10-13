package com.rydex.payment.payment;

import com.rydex.events.PaymentPayload.Kind;
import com.rydex.events.PaymentPayload.Provider;
import com.rydex.events.PaymentPayload.Status;
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
 * What a rider owes for one ride, and what happened to it. Status changes:
 * <pre>
 * PENDING ──charged──► SUCCEEDED ──refunded in full──► REFUNDED
 *    │  ▲                  ▲
 * declined └─retry─ FAILED ─┘ (Razorpay: a later checkout attempt succeeded)
 * </pre>
 * {@code nextAttemptAt} is when {@link PaymentProcessor} should next act on it: start the charge,
 * or (Razorpay) check whether the rider has paid. Null means nothing to do. Optimistic locking
 * keeps the processor, the checkout callback and the webhook from overwriting each other.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    @Column(name = "ride_id", nullable = false, updatable = false)
    private UUID rideId;

    @Column(name = "rider_id", nullable = false, updatable = false)
    private UUID riderId;

    @Column(name = "driver_id", updatable = false)
    private UUID driverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Kind kind;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "refunded_amount", nullable = false)
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    @Column(name = "refunding_amount", nullable = false)
    private BigDecimal refundingAmount = BigDecimal.ZERO;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Provider provider;

    @Column(name = "provider_ref")
    private String providerRef;

    @Column(name = "provider_payment_id")
    private String providerPaymentId;

    @Column(name = "failure_reason")
    private String failureReason;

    /** Incremented by the processor's claim (plain SQL), so never written from here. */
    @Column(insertable = false, updatable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Version
    private Long version;

    protected Payment() {
    }

    /** A new charge, due at once. */
    static Payment create(UUID rideId, UUID riderId, UUID driverId, Kind kind, BigDecimal amount, String currency,
                          Provider provider, Instant now) {
        var p = new Payment();
        p.id = UUID.randomUUID();
        p.rideId = rideId;
        p.riderId = riderId;
        p.driverId = driverId;
        p.kind = kind;
        p.amount = amount;
        p.currency = currency;
        p.provider = provider;
        p.status = Status.PENDING;
        p.nextAttemptAt = now;
        p.createdAt = now;
        p.updatedAt = now;
        return p;
    }

    /** The provider has not been asked yet (or the last attempt to ask it failed). */
    boolean needsStart() {
        return status == Status.PENDING && providerRef == null;
    }

    /** Razorpay: an order exists and the rider pays it in checkout. */
    public boolean awaitsCustomer() {
        return provider == Provider.RAZORPAY && providerRef != null
                && (status == Status.PENDING || status == Status.FAILED);
    }

    /** The money arrived. Returns false if it already had (a repeated confirmation). */
    boolean charged(String ref, String paymentId, Instant now) {
        if (status == Status.SUCCEEDED || status == Status.REFUNDED) {
            return false;
        }
        status = Status.SUCCEEDED;
        if (ref != null) {
            providerRef = ref;
        }
        providerPaymentId = paymentId;
        failureReason = null;
        paidAt = now;
        nextAttemptAt = null;
        updatedAt = now;
        return true;
    }

    /**
     * The charge was declined. {@code checkAgainAt} is null for the mock (the rider must retry) and
     * set for Razorpay, where the rider can try again in the same checkout. Returns false when the
     * payment is no longer pending.
     */
    boolean declined(String ref, String reason, Instant checkAgainAt, Instant now) {
        if (status != Status.PENDING) {
            return false;
        }
        status = Status.FAILED;
        if (ref != null) {
            providerRef = ref;
        }
        failureReason = reason;
        nextAttemptAt = checkAgainAt;
        updatedAt = now;
        return true;
    }

    /** Razorpay: the order is created; the rider pays it in checkout. */
    void awaitCustomer(String orderId, Instant firstCheckAt, Instant now) {
        providerRef = orderId;
        nextAttemptAt = firstCheckAt;
        updatedAt = now;
    }

    /**
     * The rider tries again after a decline: the mock needs a new charge; a Razorpay order stays
     * (the rider pays it again in checkout) unless it was never created. The caller schedules it.
     */
    void retry(Instant now) {
        if (status != Status.FAILED) {
            throw new IllegalStateException("only a failed payment can be retried");
        }
        status = Status.PENDING;
        if (provider == Provider.MOCK) {
            providerRef = null;
        }
        failureReason = null;
        updatedAt = now;
    }

    void scheduleNext(Instant at, Instant now) {
        nextAttemptAt = at;
        updatedAt = now;
    }

    /** INR that can still be refunded, refunds in flight excluded. */
    public BigDecimal refundable() {
        return status == Status.SUCCEEDED ? amount.subtract(refundedAmount).subtract(refundingAmount) : BigDecimal.ZERO;
    }

    void reserveRefund(BigDecimal value, Instant now) {
        if (value.compareTo(refundable()) > 0) {
            throw new IllegalArgumentException("refund exceeds what is refundable");
        }
        refundingAmount = refundingAmount.add(value);
        updatedAt = now;
    }

    /** A refund went through; the payment is REFUNDED once nothing is left. */
    void refunded(BigDecimal value, Instant now) {
        refundingAmount = refundingAmount.subtract(value);
        refundedAmount = refundedAmount.add(value);
        if (refundedAmount.compareTo(amount) >= 0) {
            status = Status.REFUNDED;
        }
        updatedAt = now;
    }

    void refundFailed(BigDecimal value, Instant now) {
        refundingAmount = refundingAmount.subtract(value);
        updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRideId() {
        return rideId;
    }

    public UUID getRiderId() {
        return riderId;
    }

    public UUID getDriverId() {
        return driverId;
    }

    public Kind getKind() {
        return kind;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getRefundedAmount() {
        return refundedAmount;
    }

    public BigDecimal getRefundingAmount() {
        return refundingAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public Status getStatus() {
        return status;
    }

    public Provider getProvider() {
        return provider;
    }

    public String getProviderRef() {
        return providerRef;
    }

    public String getProviderPaymentId() {
        return providerPaymentId;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }
}
