package com.rydex.events;

/** Every event type that flows through Kafka. Topic placement is fixed per type. */
public enum EventType {
    RIDE_REQUESTED(Topics.RIDE_EVENTS),
    DRIVER_ASSIGNED(Topics.RIDE_EVENTS),
    NO_DRIVER_FOUND(Topics.RIDE_EVENTS),
    DRIVER_ARRIVED(Topics.RIDE_EVENTS),
    RIDE_STARTED(Topics.RIDE_EVENTS),
    RIDE_COMPLETED(Topics.RIDE_EVENTS),
    RIDE_CANCELLED(Topics.RIDE_EVENTS),
    /** the rider rated a completed trip (1-5 stars): driver-service updates the driver's rating */
    RIDE_RATED(Topics.RIDE_EVENTS),
    PAYMENT_INITIATED(Topics.PAYMENT_EVENTS),
    PAYMENT_SUCCEEDED(Topics.PAYMENT_EVENTS),
    PAYMENT_FAILED(Topics.PAYMENT_EVENTS),
    PAYMENT_REFUNDED(Topics.PAYMENT_EVENTS);

    private final String topic;

    EventType(String topic) {
        this.topic = topic;
    }

    public String topic() {
        return topic;
    }
}
