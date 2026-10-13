package com.rydex.ride.events;

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

/** ride.events consumer (group ride-service): only driver-service's matching outcomes matter here. */
@Component
class RideEventsListener {

    private static final Set<EventType> HANDLED = EnumSet.of(EventType.DRIVER_ASSIGNED, EventType.NO_DRIVER_FOUND);

    private final EventCodec codec;
    private final MatchingOutcomeHandler handler;

    RideEventsListener(EventCodec codec, MatchingOutcomeHandler handler) {
        this.codec = codec;
        this.handler = handler;
    }

    @KafkaListener(id = "ride-service.ride-events", idIsGroup = false, topics = Topics.RIDE_EVENTS)
    void onRideEvent(ConsumerRecord<String, String> record) {
        if (!EventCodec.mayBeOneOf(record, HANDLED)) {
            return; // our own events and ones meant for other services
        }
        EventEnvelope<RidePayload> event = codec.rideEvent(record.value());
        if (HANDLED.contains(event.eventType())) {
            handler.apply(event);
        }
    }
}
