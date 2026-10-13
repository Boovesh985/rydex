package com.rydex.driver.events;

import com.rydex.events.EventEnvelope;
import com.rydex.events.EventType;
import com.rydex.events.RidePayload;
import com.rydex.events.Topics;
import com.rydex.messaging.EventCodec;
import java.util.EnumSet;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** ride.events consumer (group driver-service). Records are keyed by ride id, so one ride's events arrive in order. */
@Component
class RideEventsListener {

    private static final Set<EventType> HANDLED =
            EnumSet.of(EventType.RIDE_REQUESTED, EventType.RIDE_CANCELLED, EventType.RIDE_COMPLETED);

    private final EventCodec codec;
    private final RideLifecycleHandler handler;

    RideEventsListener(EventCodec codec, RideLifecycleHandler handler) {
        this.codec = codec;
        this.handler = handler;
    }

    @KafkaListener(id = "driver-service.ride-events", idIsGroup = false, topics = Topics.RIDE_EVENTS)
    void onRideEvent(ConsumerRecord<String, String> record) {
        if (!EventCodec.mayBeOneOf(record, HANDLED)) {
            return; // our own matching outcomes and ride steps we don't track
        }
        EventEnvelope<RidePayload> event = codec.rideEvent(record.value());
        if (HANDLED.contains(event.eventType())) {
            handler.apply(event);
        }
    }
}
