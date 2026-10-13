package com.rydex.ride.events;

import com.rydex.events.Topics;
import com.rydex.messaging.EventCodec;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** payment.events consumer (group ride-service): every payment event updates the ride's payment status. */
@Component
class PaymentEventsListener {

    private final EventCodec codec;
    private final PaymentOutcomeHandler handler;

    PaymentEventsListener(EventCodec codec, PaymentOutcomeHandler handler) {
        this.codec = codec;
        this.handler = handler;
    }

    @KafkaListener(id = "ride-service.payment-events", idIsGroup = false, topics = Topics.PAYMENT_EVENTS)
    void onPaymentEvent(ConsumerRecord<String, String> record) {
        handler.apply(codec.paymentEvent(record.value()));
    }
}
