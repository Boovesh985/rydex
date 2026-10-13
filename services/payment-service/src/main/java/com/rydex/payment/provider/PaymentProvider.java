package com.rydex.payment.provider;

import com.rydex.events.PaymentPayload.Provider;
import com.rydex.payment.payment.Payment;
import com.rydex.payment.payment.Refund;

/**
 * A way to take and return money. Calls happen outside database transactions; a provider that
 * cannot be reached throws {@link ProviderUnavailableException} and the processor tries again later,
 * so every call must be safe to repeat.
 */
public interface PaymentProvider {

    Provider id();

    /** Starts charging a new payment. */
    ChargeOutcome start(Payment payment);

    /** For a payment that {@link Payment#awaitsCustomer() awaits the customer}: has it been paid? */
    ChargeOutcome check(Payment payment);

    /** Returns {@code refund.amount} of a succeeded payment. */
    RefundOutcome refund(Payment payment, Refund refund);

    /** For a refund the provider accepted but has not processed yet. */
    RefundOutcome checkRefund(Payment payment, Refund refund);

    sealed interface ChargeOutcome {
    }

    /** The money arrived. {@code paymentId} is the provider's id for the successful attempt, if any. */
    record Charged(String ref, String paymentId) implements ChargeOutcome {
    }

    /** Declined; {@code reason} is safe to show the rider. */
    record Declined(String ref, String reason) implements ChargeOutcome {
    }

    /** The rider must pay through the provider's checkout; {@code orderId} identifies the order. */
    record AwaitingCustomer(String orderId) implements ChargeOutcome {
    }

    /** Nothing new: the rider has not paid yet. */
    record Waiting() implements ChargeOutcome {
    }

    sealed interface RefundOutcome {
    }

    record Processed(String ref) implements RefundOutcome {
    }

    /** Accepted, money not returned yet: check again later. */
    record Accepted(String ref) implements RefundOutcome {
    }

    record Rejected(String reason) implements RefundOutcome {
    }
}
