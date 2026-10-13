package com.rydex.payment.razorpay;

import com.rydex.events.PaymentPayload.Provider;
import com.rydex.payment.payment.Payment;
import com.rydex.payment.payment.Refund;
import com.rydex.payment.provider.PaymentProvider;
import com.rydex.payment.razorpay.RazorpayClient.RazorpayApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Razorpay test mode. A charge is a Razorpay order; the rider pays it in Razorpay Checkout (card,
 * UPI, netbanking: all simulated in test mode), and the money counts once the attempt is captured.
 * Rydex learns about it from the checkout callback, the webhook, or by asking (see {@link #check}),
 * whichever comes first.
 */
public class RazorpayProvider implements PaymentProvider {

    private final RazorpayClient razorpay;

    public RazorpayProvider(RazorpayClient razorpay) {
        this.razorpay = razorpay;
    }

    @Override
    public Provider id() {
        return Provider.RAZORPAY;
    }

    /** Creates the order. A crash before it is saved leaves an unpaid order behind: harmless. */
    @Override
    public ChargeOutcome start(Payment payment) {
        try {
            var order = razorpay.createOrder(paise(payment.getAmount()), payment.getCurrency(),
                    payment.getId().toString(), Map.of(
                            "ride_id", payment.getRideId().toString(),
                            "payment_id", payment.getId().toString(),
                            "kind", payment.getKind().name()));
            return new AwaitingCustomer(order.id());
        } catch (RazorpayApiException e) {
            return new Declined(null, "Razorpay refused the order: " + e.getMessage());
        }
    }

    /**
     * Looks at the order's attempts: a captured one means paid; an authorized one is captured now
     * (accounts can be set to capture manually); if the latest attempt failed, the payment is
     * declined (the rider can still try again in checkout).
     */
    @Override
    public ChargeOutcome check(Payment payment) {
        List<RazorpayClient.Payment> attempts = razorpay.orderPayments(payment.getProviderRef());
        Optional<RazorpayClient.Payment> captured = attempts.stream().filter(a -> "captured".equals(a.status())).findFirst();
        if (captured.isPresent()) {
            return new Charged(payment.getProviderRef(), captured.get().id());
        }
        Optional<RazorpayClient.Payment> authorized = attempts.stream().filter(a -> "authorized".equals(a.status())).findFirst();
        if (authorized.isPresent()) {
            return capture(payment, authorized.get());
        }
        return attempts.stream().max(Comparator.comparingLong(RazorpayClient.Payment::createdAt))
                .filter(latest -> "failed".equals(latest.status()))
                .<ChargeOutcome>map(latest -> new Declined(payment.getProviderRef(),
                        latest.errorDescription() != null ? latest.errorDescription() : "Payment failed"))
                .orElse(new Waiting());
    }

    /**
     * After a checkout callback: the attempt must belong to this payment's order and be captured
     * (or capturable) for the full amount. Throws {@link IllegalArgumentException} for an attempt
     * of another order, {@link RazorpayApiException} for one Razorpay does not know.
     */
    public ChargeOutcome confirm(Payment payment, String razorpayPaymentId) {
        RazorpayClient.Payment attempt = razorpay.payment(razorpayPaymentId);
        if (!payment.getProviderRef().equals(attempt.orderId())) {
            throw new IllegalArgumentException("That Razorpay payment belongs to another order");
        }
        return switch (attempt.status()) {
            case "captured" -> new Charged(payment.getProviderRef(), attempt.id());
            case "authorized" -> capture(payment, attempt);
            case "failed" -> new Declined(payment.getProviderRef(),
                    attempt.errorDescription() != null ? attempt.errorDescription() : "Payment failed");
            default -> new Waiting();
        };
    }

    private ChargeOutcome capture(Payment payment, RazorpayClient.Payment attempt) {
        if (attempt.amount() != paise(payment.getAmount())) {
            return new Declined(payment.getProviderRef(), "Amount paid does not match the amount due");
        }
        var captured = razorpay.capture(attempt.id(), attempt.amount(), payment.getCurrency());
        return "captured".equals(captured.status())
                ? new Charged(payment.getProviderRef(), captured.id())
                : new Waiting();
    }

    /**
     * Refunds are created with Rydex's refund id in their notes. A retry (the first call may have
     * gone through before Rydex could save the answer) first looks for that refund, so the rider is
     * never refunded twice.
     */
    @Override
    public RefundOutcome refund(Payment payment, Refund refund) {
        String razorpayPayment = payment.getProviderPaymentId();
        if (razorpayPayment == null) {
            return new Rejected("The payment has no captured Razorpay payment to refund");
        }
        try {
            if (refund.getAttempts() > 1) {
                Optional<RazorpayClient.RefundEntity> earlier = razorpay.refunds(razorpayPayment).stream()
                        .filter(r -> refund.getId().toString().equals(r.refundId()))
                        .findFirst();
                if (earlier.isPresent()) {
                    return outcome(earlier.get());
                }
            }
            return outcome(razorpay.refund(razorpayPayment, paise(refund.getAmount()), refund.getId().toString(),
                    Map.of("refund_id", refund.getId().toString(), "reason", refund.getReason())));
        } catch (RazorpayApiException e) {
            return new Rejected("Razorpay refused the refund: " + e.getMessage());
        }
    }

    @Override
    public RefundOutcome checkRefund(Payment payment, Refund refund) {
        return outcome(razorpay.refundById(refund.getProviderRef()));
    }

    private static RefundOutcome outcome(RazorpayClient.RefundEntity refund) {
        return switch (refund.status()) {
            case "processed" -> new Processed(refund.id());
            case "failed" -> new Rejected("Razorpay could not process the refund");
            default -> new Accepted(refund.id());
        };
    }

    /** INR to paise: Razorpay amounts are integers in the smallest unit. */
    public static long paise(BigDecimal inr) {
        return inr.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }
}
