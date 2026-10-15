package com.rydex.payment.payment;

import com.rydex.events.PaymentPayload.Kind;
import com.rydex.events.PaymentPayload.Provider;
import com.rydex.events.PaymentPayload.Status;
import com.rydex.payment.config.PaymentProperties;
import com.rydex.payment.payment.PaymentDtos.CheckoutView;
import com.rydex.payment.payment.PaymentDtos.ConfigView;
import com.rydex.payment.payment.PaymentDtos.PageView;
import com.rydex.payment.payment.PaymentDtos.PaymentView;
import com.rydex.payment.payment.PaymentDtos.RefundView;
import com.rydex.payment.provider.PaymentProviders;
import com.rydex.payment.security.AuthUser;
import com.rydex.payment.support.AfterCommit;
import com.rydex.payment.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What riders, drivers and admins can do with payments; state changes go through {@link PaymentLedger}. */
@Service
public class PaymentService {

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentLedger ledger;
    private final PaymentProcessor processor;
    private final PaymentProviders providers;
    private final PaymentProperties props;

    PaymentService(PaymentRepository payments, RefundRepository refunds, PaymentLedger ledger,
                   PaymentProcessor processor, PaymentProviders providers, PaymentProperties props) {
        this.payments = payments;
        this.refunds = refunds;
        this.ledger = ledger;
        this.processor = processor;
        this.providers = providers;
        this.props = props;
    }

    /**
     * Charges the rider for a ride, once: a ride has at most one payment. Joins the caller's
     * transaction; the charge starts once it commits. An automated (NPC) rider of the living city
     * always pays through the mock provider: no gateway order is ever opened for a pretend person.
     */
    @Transactional
    public boolean chargeForRide(UUID rideId, UUID riderId, UUID driverId, Kind kind, BigDecimal amount,
                                 boolean simulatedRider) {
        if (payments.existsByRideId(rideId)) {
            return false;
        }
        Payment p = ledger.create(rideId, riderId, driverId, kind, amount.setScale(2, RoundingMode.HALF_UP),
                simulatedRider ? Provider.MOCK : providers.active());
        AfterCommit.run(() -> processor.kick(p.getId()));
        return true;
    }

    public ConfigView config() {
        String keyId = providers.active() == Provider.RAZORPAY ? props.razorpay().keyId() : null;
        return new ConfigView(providers.active(), props.currency(), keyId);
    }

    /** The ride's rider, its driver and admins; anyone else gets 404. */
    @Transactional(readOnly = true)
    public PaymentView forRide(AuthUser caller, UUID rideId) {
        Payment p = payments.findByRideId(rideId).orElseThrow(() -> ApiException.notFound("Payment"));
        requireAccess(caller, p);
        return view(p, caller);
    }

    @Transactional(readOnly = true)
    public PageView<PaymentView> mine(AuthUser caller, int page, int size) {
        var pageable = PageRequest.of(page, size);
        Page<Payment> result = switch (caller.role()) {
            case "RIDER" -> payments.findByRiderIdOrderByCreatedAtDesc(caller.id(), pageable);
            case "DRIVER" -> payments.findByDriverIdOrderByCreatedAtDesc(caller.id(), pageable);
            default -> Page.empty(pageable);
        };
        return new PageView<>(result.map(p -> view(p, caller)).getContent(), page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public PageView<PaymentView> list(Status status, int page, int size) {
        var pageable = PageRequest.of(page, size);
        Page<Payment> result = status == null
                ? payments.findAllByOrderByCreatedAtDesc(pageable)
                : payments.findByStatusOrderByCreatedAtDesc(status, pageable);
        return new PageView<>(result.map(p -> view(p, null)).getContent(), page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public PaymentView get(AuthUser caller, UUID paymentId) {
        Payment p = load(paymentId);
        requireAccess(caller, p);
        return view(p, caller);
    }

    /** The rider pays again after a decline: the mock charges again, Razorpay reopens the same order. */
    @Transactional
    public PaymentView retry(AuthUser rider, UUID paymentId) {
        Payment p = loadOwn(rider, paymentId);
        if (p.getStatus() != Status.FAILED) {
            throw ApiException.conflict("Only a failed payment can be retried (this one is " + p.getStatus() + ")");
        }
        Payment updated = ledger.retry(paymentId);
        if (updated.needsStart()) {
            AfterCommit.run(() -> processor.kick(paymentId));
        }
        return view(updated, rider);
    }

    /** Admin refund of all ({@code amount} null) or part of what is left. */
    @Transactional
    public PaymentView refund(AuthUser admin, UUID paymentId, BigDecimal amount, String reason) {
        Payment p = load(paymentId);
        if (p.getStatus() != Status.SUCCEEDED) {
            throw ApiException.conflict("Only a succeeded payment can be refunded (this one is " + p.getStatus() + ")");
        }
        BigDecimal refundable = p.refundable();
        BigDecimal value = amount == null ? refundable : amount.setScale(2, RoundingMode.UNNECESSARY);
        if (refundable.signum() <= 0) {
            throw ApiException.conflict("Nothing left to refund: Rs " + p.getRefundedAmount().toPlainString()
                    + " refunded, Rs " + p.getRefundingAmount().toPlainString() + " being refunded");
        }
        if (value.compareTo(refundable) > 0) {
            throw ApiException.unprocessable("At most Rs " + refundable.toPlainString() + " can be refunded");
        }
        Refund r = ledger.requestRefund(paymentId, value, reason.trim(), admin.id());
        AfterCommit.run(() -> processor.kickRefund(r.getId()));
        return view(p, admin);
    }

    /** For the Razorpay checkout callback: the caller's own payment, which must await them. */
    @Transactional(readOnly = true)
    public Payment awaitingRazorpay(AuthUser rider, UUID paymentId) {
        Payment p = loadOwn(rider, paymentId);
        if (p.getProvider() != Provider.RAZORPAY || p.getProviderRef() == null) {
            throw ApiException.conflict("This payment is not paid through Razorpay Checkout");
        }
        return p;
    }

    @Transactional(readOnly = true)
    public PaymentView view(UUID paymentId, AuthUser caller) {
        return view(load(paymentId), caller);
    }

    private PaymentView view(Payment p, AuthUser caller) {
        List<RefundView> refundViews = refunds.findByPaymentIdOrderByCreatedAtAsc(p.getId()).stream()
                .map(RefundView::of)
                .toList();
        return PaymentView.of(p, refundViews, checkoutFor(p, caller));
    }

    /** Only the rider sees checkout options, and only while a Razorpay order awaits them. */
    private CheckoutView checkoutFor(Payment p, AuthUser caller) {
        if (caller == null || !caller.id().equals(p.getRiderId()) || !p.awaitsCustomer()) {
            return null;
        }
        String what = p.getKind() == Kind.RIDE_FARE ? "Ride fare" : "Cancellation fee";
        return new CheckoutView(props.razorpay().keyId(), p.getProviderRef(),
                p.getAmount().movePointRight(2).longValueExact(), p.getCurrency(), props.razorpay().businessName(),
                what + " · ride " + p.getRideId().toString().substring(0, 8));
    }

    private Payment load(UUID paymentId) {
        return payments.findById(paymentId).orElseThrow(() -> ApiException.notFound("Payment"));
    }

    private Payment loadOwn(AuthUser rider, UUID paymentId) {
        Payment p = load(paymentId);
        if (!rider.id().equals(p.getRiderId())) {
            throw ApiException.notFound("Payment");
        }
        return p;
    }

    private static void requireAccess(AuthUser caller, Payment p) {
        boolean allowed = caller.isAdmin() || caller.id().equals(p.getRiderId()) || caller.id().equals(p.getDriverId());
        if (!allowed) {
            throw ApiException.notFound("Payment");
        }
    }
}
