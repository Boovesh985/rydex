package com.rydex.tracking.payment;

import com.rydex.events.Topics;
import com.rydex.messaging.EventCodec;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** payment.events consumer (group tracking-service): every payment step notifies the rider. */
@Component
class PaymentEventsListener {

    private final EventCodec codec;
    private final PaymentNotifications notifications;

    PaymentEventsListener(EventCodec codec, PaymentNotifications notifications) {
        this.codec = codec;
        this.notifications = notifications;
    }

    @KafkaListener(id = "tracking-service.payment-events", idIsGroup = false, topics = Topics.PAYMENT_EVENTS)
    void onPaymentEvent(ConsumerRecord<String, String> record) {
        notifications.apply(codec.paymentEvent(record.value()));
    }
}
