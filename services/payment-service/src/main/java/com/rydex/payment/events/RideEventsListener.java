package com.rydex.payment.events;

import com.rydex.events.EventType;
import com.rydex.events.Topics;
import com.rydex.messaging.EventCodec;
import java.util.EnumSet;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** ride.events consumer (group payment-service): only the end of a ride can cost money. */
@Component
class RideEventsListener {

    private static final Set<EventType> HANDLED = EnumSet.of(EventType.RIDE_COMPLETED, EventType.RIDE_CANCELLED);

    private final EventCodec codec;
    private final RideChargeHandler handler;

    RideEventsListener(EventCodec codec, RideChargeHandler handler) {
        this.codec = codec;
        this.handler = handler;
    }

    @KafkaListener(id = "payment-service.ride-events", idIsGroup = false, topics = Topics.RIDE_EVENTS)
    void onRideEvent(ConsumerRecord<String, String> record) {
        if (!EventCodec.mayBeOneOf(record, HANDLED)) {
            return;
        }
        var event = codec.rideEvent(record.value());
        if (HANDLED.contains(event.eventType())) {
            handler.apply(event);
        }
    }
}
