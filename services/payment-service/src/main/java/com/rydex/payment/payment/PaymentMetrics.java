package com.rydex.payment.payment;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** {@code payment_failures_total} (master prompt §14) and its happy-path counterparts, per provider and kind. */
@Component
class PaymentMetrics {

    private final MeterRegistry registry;

    PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void succeeded(Payment p) {
        counter("payments_succeeded_total", p).increment();
    }

    void failed(Payment p) {
        counter("payment_failures_total", p).increment();
    }

    void refunded(Payment p) {
        counter("payment_refunds_total", p).increment();
    }

    private Counter counter(String name, Payment p) {
        return Counter.builder(name)
                .tag("provider", p.getProvider().name())
                .tag("kind", p.getKind().name())
                .register(registry);
    }
}
