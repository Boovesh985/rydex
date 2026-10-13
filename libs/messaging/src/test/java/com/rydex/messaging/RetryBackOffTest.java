package com.rydex.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

class RetryBackOffTest {

    private static List<Long> waits(MessagingProperties.Retry retry) {
        BackOff backOff = MessagingConfiguration.backOff(retry);
        BackOffExecution execution = backOff.start();
        List<Long> waits = new ArrayList<>();
        for (long next = execution.nextBackOff(); next != BackOffExecution.STOP; next = execution.nextBackOff()) {
            waits.add(next);
        }
        return waits;
    }

    @Test
    void defaultsRideOutAFewMinutesOfOutageBeforeDeadLettering() {
        MessagingProperties defaults = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("rydex.messaging", MessagingProperties.class);

        List<Long> waits = waits(defaults.retry());

        assertThat(waits).containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L,
                60_000L, 60_000L, 60_000L, 60_000L);
        assertThat(waits.stream().mapToLong(Long::longValue).sum()).isGreaterThan(Duration.ofMinutes(4).toMillis());
        // every single wait stays under Kafka's max.poll.interval.ms (5 minutes)
        assertThat(waits).allMatch(w -> w < Duration.ofMinutes(5).toMillis());
    }
}
