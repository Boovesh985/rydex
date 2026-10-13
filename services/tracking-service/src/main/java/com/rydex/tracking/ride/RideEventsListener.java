package com.rydex.tracking.ride;

import com.rydex.events.EventType;
import com.rydex.events.Topics;
import com.rydex.messaging.EventCodec;
import java.util.EnumSet;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** ride.events consumer (group tracking-service): every ride step updates the projection and notifies. */
@Component
class RideEventsListener {

    private static final Set<EventType> HANDLED = EnumSet.of(EventType.RIDE_REQUESTED, EventType.DRIVER_ASSIGNED,
            EventType.NO_DRIVER_FOUND, EventType.DRIVER_ARRIVED, EventType.RIDE_STARTED, EventType.RIDE_COMPLETED,
            EventType.RIDE_CANCELLED);

    private final EventCodec codec;
    private final RideProjection projection;

    RideEventsListener(EventCodec codec, RideProjection projection) {
        this.codec = codec;
        this.projection = projection;
    }

    @KafkaListener(id = "tracking-service.ride-events", idIsGroup = false, topics = Topics.RIDE_EVENTS)
    void onRideEvent(ConsumerRecord<String, String> record) {
        if (!EventCodec.mayBeOneOf(record, HANDLED)) {
            return;
        }
        var event = codec.rideEvent(record.value());
        if (HANDLED.contains(event.eventType())) {
            projection.apply(event);
        }
    }
}
