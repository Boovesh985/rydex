package com.rydex.payment.payment;

import com.rydex.events.EventType;
import com.rydex.events.PaymentPayload.Kind;
import com.rydex.events.PaymentPayload.Provider;
import com.rydex.payment.config.PaymentProperties;
import com.rydex.payment.provider.PaymentProvider.Accepted;
import com.rydex.payment.provider.PaymentProvider.AwaitingCustomer;
import com.rydex.payment.provider.PaymentProvider.ChargeOutcome;
import com.rydex.payment.provider.PaymentProvider.Charged;
import com.rydex.payment.provider.PaymentProvider.Declined;
import com.rydex.payment.provider.PaymentProvider.Processed;
import com.rydex.payment.provider.PaymentProvider.RefundOutcome;
import com.rydex.payment.provider.PaymentProvider.Rejected;
import com.rydex.payment.provider.PaymentProvider.Waiting;
import com.rydex.payment.support.AfterCommit;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every change to a payment or refund, each in one transaction with its {@code payment.events}
 * event. Provider calls never happen in here: callers make them first and record the outcome.
 * Recording is idempotent, so the checkout callback, the webhook and the processor can all report
 * the same outcome.
 */
@Component
public class PaymentLedger {

    private static final Logger log = LoggerFactory.getLogger(PaymentLedger.class);

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentEvents events;
    private final PaymentMetrics metrics;
    private final PaymentProperties props;
    private final Clock clock;

    PaymentLedger(PaymentRepository payments, RefundRepository refunds, PaymentEvents events, PaymentMetrics metrics,
                  PaymentProperties props, Clock clock) {
        this.payments = payments;
        this.refunds = refunds;
        this.events = events;
        this.metrics = metrics;
        this.props = props;
        this.clock = clock;
    }

    /** A new payment, due at once; must join the transaction that consumed the ride event. */
    @Transactional
    public Payment create(UUID rideId, UUID riderId, UUID driverId, Kind kind, BigDecimal amount, Provider provider) {
        return payments.save(Payment.create(rideId, riderId, driverId, kind, amount, props.currency(), provider,
                clock.instant()));
    }

    @Transactional
    public Payment recordCharge(UUID paymentId, ChargeOutcome outcome) {
        Payment p = payments.findById(paymentId).orElseThrow();
        Instant now = clock.instant();
        switch (outcome) {
            case Charged c -> {
                if (p.charged(c.ref(), c.paymentId(), now)) {
                    events.publish(EventType.PAYMENT_SUCCEEDED, p, null, null);
                    AfterCommit.run(() -> metrics.succeeded(p));
                    log.info("Payment {} for ride {} succeeded: Rs {} via {}", p.getId(), p.getRideId(),
                            p.getAmount().toPlainString(), p.getProvider());
                }
            }
            case Declined d -> {
                // Razorpay: the rider can try again in the same checkout, so keep watching the order
                boolean watch = p.getProvider() == Provider.RAZORPAY && (d.ref() != null || p.getProviderRef() != null);
                if (p.declined(d.ref(), d.reason(), watch ? now.plus(checkDelay(p)) : null, now)) {
                    events.publish(EventType.PAYMENT_FAILED, p, null, d.reason());
                    AfterCommit.run(() -> metrics.failed(p));
                    log.info("Payment {} for ride {} failed: {}", p.getId(), p.getRideId(), d.reason());
                } else if (p.awaitsCustomer()) {
                    p.scheduleNext(now.plus(checkDelay(p)), now);
                }
            }
            case AwaitingCustomer a -> {
                if (p.needsStart()) {
                    p.awaitCustomer(a.orderId(), now.plus(props.razorpay().checkInterval()), now);
                    events.publish(EventType.PAYMENT_INITIATED, p, null, null);
                    log.info("Payment {} for ride {} awaits the rider: order {}", p.getId(), p.getRideId(), a.orderId());
                }
            }
            case Waiting w -> {
                if (p.awaitsCustomer()) {
                    p.scheduleNext(now.plus(checkDelay(p)), now);
                }
            }
        }
        return p;
    }

    /** The provider could not be reached: try again later, backing off. */
    @Transactional
    public void retryLater(UUID paymentId, String error) {
        Payment p = payments.findById(paymentId).orElseThrow();
        Instant now = clock.instant();
        p.scheduleNext(now.plus(backoff(Duration.ofSeconds(1), props.retryMaxDelay(), p.getAttempts() - 1)), now);
        log.warn("Payment {} attempt {} failed, retrying at {}: {}", paymentId, p.getAttempts(), p.getNextAttemptAt(),
                error);
    }

    /** Stops checking an order nobody paid for. */
    @Transactional
    public void stopChecking(UUID paymentId) {
        Payment p = payments.findById(paymentId).orElseThrow();
        p.scheduleNext(null, clock.instant());
        log.info("Payment {} for ride {} was not paid in time; no longer checking order {}", p.getId(), p.getRideId(),
                p.getProviderRef());
    }

    /** Check this payment's order now (a webhook said something happened to it). */
    @Transactional
    public void checkSoon(UUID paymentId) {
        Payment p = payments.findById(paymentId).orElseThrow();
        if (p.awaitsCustomer()) {
            p.scheduleNext(clock.instant(), clock.instant());
        }
    }

    @Transactional
    public Payment retry(UUID paymentId) {
        Payment p = payments.findById(paymentId).orElseThrow();
        Instant now = clock.instant();
        p.retry(now);
        p.scheduleNext(p.needsStart() ? now : now.plus(props.razorpay().checkInterval()), now);
        return p;
    }

    @Transactional
    public Refund requestRefund(UUID paymentId, BigDecimal amount, String reason, UUID requestedBy) {
        Payment p = payments.findById(paymentId).orElseThrow();
        Instant now = clock.instant();
        p.reserveRefund(amount, now);
        return refunds.save(Refund.request(p.getId(), amount, reason, requestedBy, now));
    }

    @Transactional
    public Refund recordRefund(UUID refundId, RefundOutcome outcome) {
        Refund r = refunds.findById(refundId).orElseThrow();
        Payment p = payments.findById(r.getPaymentId()).orElseThrow();
        Instant now = clock.instant();
        switch (outcome) {
            case Processed done -> {
                if (r.processed(done.ref(), now)) {
                    p.refunded(r.getAmount(), now);
                    events.publish(EventType.PAYMENT_REFUNDED, p, r.getAmount(), r.getReason());
                    AfterCommit.run(() -> metrics.refunded(p));
                    log.info("Refunded Rs {} of payment {} (ride {}): {}", r.getAmount().toPlainString(), p.getId(),
                            p.getRideId(), r.getReason());
                }
            }
            case Accepted accepted -> {
                if (r.getStatus() == Refund.Status.PENDING) {
                    r.accepted(accepted.ref(), now.plus(backoff(props.razorpay().checkInterval(),
                            props.razorpay().maxCheckInterval(), r.getAttempts() - 1)));
                }
            }
            case Rejected rejected -> {
                if (r.failed(rejected.reason(), now)) {
                    p.refundFailed(r.getAmount(), now);
                    log.warn("Refund {} of payment {} failed: {}", r.getId(), p.getId(), rejected.reason());
                }
            }
        }
        return r;
    }

    @Transactional
    public void retryRefundLater(UUID refundId, String error) {
        Refund r = refunds.findById(refundId).orElseThrow();
        r.scheduleNext(clock.instant().plus(backoff(Duration.ofSeconds(1), props.retryMaxDelay(), r.getAttempts() - 1)));
        log.warn("Refund {} attempt {} failed, retrying: {}", refundId, r.getAttempts(), error);
    }

    /** Unpaid Razorpay orders are checked ever less often: 30 s, 1 min, 2 min ... up to the maximum. */
    private Duration checkDelay(Payment p) {
        return backoff(props.razorpay().checkInterval(), props.razorpay().maxCheckInterval(), p.getAttempts() - 1);
    }

    static Duration backoff(Duration base, Duration max, int step) {
        int doublings = Math.clamp(step, 0, 20);
        Duration delay = base.multipliedBy(1L << doublings);
        return delay.compareTo(max) > 0 ? max : delay;
    }
}
