package com.rydex.events;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Payload of every {@code payment.events} message (PAYMENT_INITIATED, PAYMENT_SUCCEEDED,
 * PAYMENT_FAILED, PAYMENT_REFUNDED). The envelope's {@code rideId} is the ride the payment is for;
 * a ride has at most one payment: its fare once completed, or its fee if cancelled late.
 *
 * @param amount         INR charged (or to be charged)
 * @param refundedAmount INR refunded so far, this refund included
 * @param refundAmount   PAYMENT_REFUNDED only: the INR this refund returned
 * @param providerRef    the provider's reference: a Razorpay order id, or the mock charge id
 * @param reason         PAYMENT_FAILED: why; PAYMENT_REFUNDED: the refund reason
 */
public record PaymentPayload(
        UUID paymentId,
        UUID riderId,
        UUID driverId,
        Kind kind,
        BigDecimal amount,
        BigDecimal refundedAmount,
        BigDecimal refundAmount,
        String currency,
        Provider provider,
        String providerRef,
        Status status,
        String reason) {

    public enum Kind {
        /** The fare of a completed ride. */
        RIDE_FARE,
        /** The fee for cancelling after the driver arrived. */
        CANCELLATION_FEE
    }

    public enum Provider {
        MOCK,
        RAZORPAY
    }

    public enum Status {
        /** Being charged, or (Razorpay) waiting for the rider to pay through checkout. */
        PENDING,
        SUCCEEDED,
        /** Declined; the rider can pay again. */
        FAILED,
        /** Fully refunded. */
        REFUNDED
    }
}
