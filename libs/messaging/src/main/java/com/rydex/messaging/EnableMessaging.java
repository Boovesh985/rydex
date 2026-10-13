package com.rydex.messaging;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * Turns on Rydex messaging for a service: the outbox writer and relay, idempotent-consumer
 * bookkeeping, retries with dead-lettering for Kafka listeners, and (optionally) topic creation.
 * The service needs the {@code outbox} and {@code processed_events} tables in its schema.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(MessagingConfiguration.class)
public @interface EnableMessaging {
}
