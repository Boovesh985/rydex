package com.rydex.payment.payment;

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
 * Money returned to the rider, all or part of a payment. PENDING until the provider confirms it
 * (Razorpay can take a while); its amount is reserved on the payment meanwhile, so two refunds can
 * never return more than was paid.
 */
@Entity
@Table(name = "refunds")
public class Refund {

    public enum Status {
        PENDING,
        PROCESSED,
        FAILED
    }

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(name = "provider_ref")
    private String providerRef;

    @Column(name = "failure_reason")
    private String failureReason;

    /** Incremented by the processor's claim (plain SQL), so never written from here. */
    @Column(insertable = false, updatable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Version
    private Long version;

    protected Refund() {
    }

    static Refund request(UUID paymentId, BigDecimal amount, String reason, UUID requestedBy, Instant now) {
        var r = new Refund();
        r.id = UUID.randomUUID();
        r.paymentId = paymentId;
        r.amount = amount;
        r.reason = reason;
        r.requestedBy = requestedBy;
        r.status = Status.PENDING;
        r.nextAttemptAt = now;
        r.createdAt = now;
        return r;
    }

    /** The provider has not accepted it yet. */
    boolean needsStart() {
        return status == Status.PENDING && providerRef == null;
    }

    /** Accepted by the provider but not processed yet: check again at {@code checkAt}. */
    void accepted(String ref, Instant checkAt) {
        providerRef = ref;
        nextAttemptAt = checkAt;
    }

    /** Returns false when it was already settled. */
    boolean processed(String ref, Instant now) {
        if (status != Status.PENDING) {
            return false;
        }
        status = Status.PROCESSED;
        if (ref != null) {
            providerRef = ref;
        }
        processedAt = now;
        nextAttemptAt = null;
        return true;
    }

    boolean failed(String reason, Instant now) {
        if (status != Status.PENDING) {
            return false;
        }
        status = Status.FAILED;
        failureReason = reason;
        processedAt = now;
        nextAttemptAt = null;
        return true;
    }

    void scheduleNext(Instant at) {
        nextAttemptAt = at;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Status getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public String getProviderRef() {
        return providerRef;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
