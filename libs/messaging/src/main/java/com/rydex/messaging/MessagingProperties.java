package com.rydex.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param createTopics create the Rydex topics on startup (tests); in compose the kafka-init container does it
 */
@ConfigurationProperties("rydex.messaging")
public record MessagingProperties(
        @DefaultValue Outbox outbox,
        @DefaultValue Retry retry,
        @DefaultValue("false") boolean createTopics) {

    /**
     * @param pollInterval    how often unpublished outbox rows are relayed
     * @param batchSize       rows per relay pass
     * @param sendTimeout     how long to wait for the broker to acknowledge one record
     * @param retention       published outbox rows and processed-event markers are deleted after this
     * @param cleanupInterval how often that cleanup runs
     */
    public record Outbox(
            @DefaultValue("500ms") Duration pollInterval,
            @DefaultValue("100") int batchSize,
            @DefaultValue("10s") Duration sendTimeout,
            @DefaultValue("14d") Duration retention,
            @DefaultValue("1h") Duration cleanupInterval) {
    }

    /** Listener retries: {@code maxRetries} attempts after the first, backing off exponentially, then the DLT. */
    public record Retry(
            @DefaultValue("1s") Duration initialInterval,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("3") int maxRetries) {
    }
}
