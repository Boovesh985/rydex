package com.rydex.tracking.location;

import com.rydex.events.DriverLocationEvent;
import com.rydex.events.Topics;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes accepted locations on {@code driver.location}, keyed by driver id. Telemetry goes
 * straight to Kafka, without the outbox: at 3 s per online driver an outbox row each would be
 * most of the database's write load, and a lost update is superseded by the next one (ADR 0011).
 */
@Component
class DriverLocationPublisher {

    static final String EVENT_TYPE = "DRIVER_LOCATION";

    private static final Logger log = LoggerFactory.getLogger(DriverLocationPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;

    DriverLocationPublisher(KafkaTemplate<String, String> kafka, JsonMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    void publish(DriverLocationEvent event) {
        var record = new ProducerRecord<>(Topics.DRIVER_LOCATION, event.key(), json.writeValueAsString(event));
        record.headers().add("eventId", event.eventId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", EVENT_TYPE.getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(record).whenComplete((result, e) -> {
                if (e != null) {
                    log.debug("Could not publish the location of driver {}: {}", event.driverId(), e.toString());
                }
            });
        } catch (RuntimeException e) {
            log.debug("Could not publish the location of driver {}: {}", event.driverId(), e.toString());
        }
    }
}
