package com.rydex.payment.payment;

import com.rydex.events.EventEnvelope;
import com.rydex.events.EventType;
import com.rydex.events.PaymentPayload;
import com.rydex.messaging.OutboxWriter;
import java.math.BigDecimal;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** Writes {@code payment.events} to the outbox, in the transaction that changed the payment. */
@Component
class PaymentEvents {

    private final OutboxWriter outbox;
    private final Clock clock;

    PaymentEvents(OutboxWriter outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    void publish(EventType type, Payment p, BigDecimal refundAmount, String reason) {
        var payload = new PaymentPayload(p.getId(), p.getRiderId(), p.getDriverId(), p.getKind(), p.getAmount(),
                p.getRefundedAmount(), refundAmount, p.getCurrency(), p.getProvider(), p.getProviderRef(),
                p.getStatus(), reason);
        outbox.append(EventEnvelope.of(type, p.getRideId().toString(), payload, clock));
    }
}
