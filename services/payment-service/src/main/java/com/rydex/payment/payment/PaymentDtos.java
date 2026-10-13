package com.rydex.payment.payment;

import com.rydex.events.PaymentPayload.Kind;
import com.rydex.events.PaymentPayload.Provider;
import com.rydex.events.PaymentPayload.Status;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    /**
     * @param refundable INR that can still be refunded
     * @param checkout   set for the rider while a Razorpay payment awaits them: what Razorpay Checkout needs
     */
    public record PaymentView(
            UUID id,
            UUID rideId,
            UUID riderId,
            UUID driverId,
            Kind kind,
            Status status,
            BigDecimal amount,
            BigDecimal refundedAmount,
            BigDecimal refundingAmount,
            BigDecimal refundable,
            String currency,
            Provider provider,
            String providerRef,
            String failureReason,
            Instant createdAt,
            Instant paidAt,
            List<RefundView> refunds,
            CheckoutView checkout) {

        static PaymentView of(Payment p, List<RefundView> refunds, CheckoutView checkout) {
            return new PaymentView(p.getId(), p.getRideId(), p.getRiderId(), p.getDriverId(), p.getKind(),
                    p.getStatus(), p.getAmount(), p.getRefundedAmount(), p.getRefundingAmount(), p.refundable(),
                    p.getCurrency(), p.getProvider(), p.getProviderRef(), p.getFailureReason(), p.getCreatedAt(),
                    p.getPaidAt(), refunds, checkout);
        }
    }

    public record RefundView(UUID id, BigDecimal amount, Refund.Status status, String reason, String failureReason,
                             Instant createdAt, Instant processedAt) {

        static RefundView of(Refund r) {
            return new RefundView(r.getId(), r.getAmount(), r.getStatus(), r.getReason(), r.getFailureReason(),
                    r.getCreatedAt(), r.getProcessedAt());
        }
    }

    /**
     * Options for Razorpay Checkout ({@code new Razorpay({key, order_id, amount, currency, name,
     * description, handler})}). The handler's response goes to {@code POST /api/payments/{id}/razorpay/verify}.
     *
     * @param amount in paise
     */
    public record CheckoutView(String keyId, String orderId, long amount, String currency, String name,
                               String description) {
    }

    /** @param razorpayKeyId public: Checkout needs it in the browser */
    public record ConfigView(Provider provider, String currency, String razorpayKeyId) {
    }

    /** @param amount INR; leave out to refund everything that is left */
    public record RefundRequest(
            @DecimalMin(value = "0.01") @Digits(integer = 8, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 300) String reason) {
    }

    /** What Razorpay Checkout's handler receives, as is. */
    public record VerifyRequest(
            @NotBlank @Size(max = 64) String razorpayOrderId,
            @NotBlank @Size(max = 64) String razorpayPaymentId,
            @NotBlank @Size(max = 128) String razorpaySignature) {
    }

    public record PageView<T>(List<T> items, int page, int size, long total) {
    }
}
