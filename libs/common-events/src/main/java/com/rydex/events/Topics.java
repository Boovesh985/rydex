package com.rydex.events;

/**
 * Kafka topic names. Topics are created by the {@code kafka-init} container
 * (see infra/kafka/create-topics.sh); auto-creation is disabled on the broker.
 */
public final class Topics {

    public static final String RIDE_EVENTS = "ride.events";
    public static final String DRIVER_LOCATION = "driver.location";
    public static final String PAYMENT_EVENTS = "payment.events";

    /** Suffix used by Spring Kafka's DeadLetterPublishingRecoverer. */
    public static final String DLT_SUFFIX = ".DLT";

    private Topics() {
    }

    public static String deadLetter(String topic) {
        return topic + DLT_SUFFIX;
    }
}
