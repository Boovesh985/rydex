package com.rydex.payment.payment;

import com.rydex.payment.config.PaymentProperties;
import com.rydex.payment.payment.WorkQueue.Table;
import com.rydex.payment.provider.PaymentProvider;
import com.rydex.payment.provider.PaymentProvider.ChargeOutcome;
import com.rydex.payment.provider.PaymentProvider.RefundOutcome;
import com.rydex.payment.provider.PaymentProviders;
import com.rydex.payment.provider.ProviderUnavailableException;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Moves payments and refunds forward by talking to their provider: starts new charges, checks
 * Razorpay orders the rider has not paid yet, and sends refunds. New work is started right after
 * its transaction commits ({@link #kick}); a sweep every {@code process-interval} picks up the
 * rest (retries, scheduled checks, anything a crash interrupted). Leases keep two workers, on
 * any instance, off the same row.
 */
@Component
public class PaymentProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessor.class);
    private static final int BATCH = 20;

    private final WorkQueue queue;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentLedger ledger;
    private final PaymentProviders providers;
    private final PaymentProperties props;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    PaymentProcessor(WorkQueue queue, PaymentRepository payments, RefundRepository refunds, PaymentLedger ledger,
                     PaymentProviders providers, PaymentProperties props, TaskScheduler scheduler, Clock clock) {
        this.queue = queue;
        this.payments = payments;
        this.refunds = refunds;
        this.ledger = ledger;
        this.providers = providers;
        this.props = props;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    void startSweeping() {
        scheduler.scheduleWithFixedDelay(this::sweep, props.processInterval());
    }

    @PreDestroy
    void stop() {
        workers.shutdown();
    }

    /** Starts work on a payment now instead of at the next sweep (call after its transaction commits). */
    public void kick(UUID paymentId) {
        workers.execute(() -> advancePayment(paymentId));
    }

    public void kickRefund(UUID refundId) {
        workers.execute(() -> advanceRefund(refundId));
    }

    /** One pass over everything due. Public for tests. */
    public void sweep() {
        try {
            Instant now = clock.instant();
            queue.due(Table.PAYMENTS, now, BATCH).forEach(this::advancePayment);
            queue.due(Table.REFUNDS, now, BATCH).forEach(this::advanceRefund);
        } catch (RuntimeException e) {
            log.warn("Payment sweep failed: {}", e.getMessage());
        }
    }

    void advancePayment(UUID id) {
        Instant now = clock.instant();
        if (!queue.claim(Table.PAYMENTS, id, now, now.plus(props.lease()))) {
            return;
        }
        try {
            Payment p = payments.findById(id).orElse(null);
            if (p == null) {
                return;
            }
            ChargeOutcome outcome;
            if (p.needsStart()) {
                outcome = providers.get(p.getProvider()).start(p);
            } else if (p.awaitsCustomer()) {
                if (p.getCreatedAt().plus(props.razorpay().giveUpAfter()).isBefore(now)) {
                    ledger.stopChecking(id);
                    return;
                }
                outcome = providers.get(p.getProvider()).check(p);
            } else {
                ledger.stopChecking(id); // settled meanwhile (e.g. by the checkout callback)
                return;
            }
            ledger.recordCharge(id, outcome);
        } catch (ProviderUnavailableException | OptimisticLockingFailureException e) {
            ledger.retryLater(id, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Payment {} could not be processed", id, e);
            ledger.retryLater(id, e.toString());
        } finally {
            queue.release(Table.PAYMENTS, id);
        }
    }

    void advanceRefund(UUID id) {
        Instant now = clock.instant();
        if (!queue.claim(Table.REFUNDS, id, now, now.plus(props.lease()))) {
            return;
        }
        try {
            Refund r = refunds.findById(id).orElse(null);
            if (r == null || r.getStatus() != Refund.Status.PENDING) {
                return;
            }
            Payment p = payments.findById(r.getPaymentId()).orElseThrow();
            PaymentProvider provider = providers.get(p.getProvider());
            RefundOutcome outcome = r.needsStart() ? provider.refund(p, r) : provider.checkRefund(p, r);
            ledger.recordRefund(id, outcome);
        } catch (ProviderUnavailableException | OptimisticLockingFailureException e) {
            ledger.retryRefundLater(id, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Refund {} could not be processed", id, e);
            ledger.retryRefundLater(id, e.toString());
        } finally {
            queue.release(Table.REFUNDS, id);
        }
    }
}
