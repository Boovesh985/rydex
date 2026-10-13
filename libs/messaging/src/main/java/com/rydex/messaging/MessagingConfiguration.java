package com.rydex.messaging;

import com.rydex.events.Topics;
import java.time.Clock;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Beans behind {@link EnableMessaging}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MessagingProperties.class)
class MessagingConfiguration {

    private static final long DAY_MS = 86_400_000L;

    @Bean
    OutboxWriter outboxWriter(JdbcClient jdbc, JsonMapper json) {
        return new OutboxWriter(jdbc, json);
    }

    @Bean
    OutboxRelay outboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                            MessagingProperties props, Clock clock) {
        return new OutboxRelay(jdbc, kafka, tx, props.outbox(), clock);
    }

    @Bean
    ProcessedEvents processedEvents(JdbcClient jdbc) {
        return new ProcessedEvents(jdbc);
    }

    @Bean
    EventCodec eventCodec(JsonMapper json) {
        return new EventCodec(json);
    }

    /**
     * Listener failures are retried with exponential backoff (about 5 minutes by default), then the
     * record goes to {@code <topic>.DLT} on the same partition. Malformed events skip the retries.
     * While a record is retried its partition waits, so later events of the same ride stay in order.
     * Spring Boot applies this handler to the default listener container factory.
     */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka, MessagingProperties props) {
        var recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, ex) -> new TopicPartition(Topics.deadLetter(record.topic()), record.partition()));
        var handler = new DefaultErrorHandler(recoverer, backOff(props.retry()));
        handler.addNotRetryableExceptions(NonRetryableEventException.class);
        return handler;
    }

    static ExponentialBackOffWithMaxRetries backOff(MessagingProperties.Retry retry) {
        var backOff = new ExponentialBackOffWithMaxRetries(retry.maxRetries());
        backOff.setInitialInterval(retry.initialInterval().toMillis());
        backOff.setMultiplier(retry.multiplier());
        // each wait stays well under max.poll.interval.ms (5 min), so the consumer keeps its partitions
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        return backOff;
    }

    /**
     * Same topics as infra/kafka/create-topics.sh (keep them in sync). Off by default: in
     * compose and production the kafka-init container creates topics and auto-creation is off.
     */
    @Bean
    @ConditionalOnProperty(name = "rydex.messaging.create-topics", havingValue = "true")
    KafkaAdmin.NewTopics rydexTopics() {
        return new KafkaAdmin.NewTopics(
                topic(Topics.RIDE_EVENTS, 6, 7 * DAY_MS),
                topic(Topics.deadLetter(Topics.RIDE_EVENTS), 6, 7 * DAY_MS),
                topic(Topics.DRIVER_LOCATION, 6, 3_600_000L),
                topic(Topics.deadLetter(Topics.DRIVER_LOCATION), 6, 7 * DAY_MS),
                topic(Topics.PAYMENT_EVENTS, 3, 7 * DAY_MS),
                topic(Topics.deadLetter(Topics.PAYMENT_EVENTS), 3, 7 * DAY_MS));
    }

    private static NewTopic topic(String name, int partitions, long retentionMs) {
        return TopicBuilder.name(name).partitions(partitions).replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(retentionMs))
                .build();
    }
}
