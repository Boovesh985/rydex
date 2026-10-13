package com.rydex.payment.payment;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Payments and refunds with work due ({@code next_attempt_at} passed), and short leases so only
 * one worker, on any instance, talks to the provider about the same one at a time. A lease left
 * behind by a crashed worker simply runs out. Claiming counts an attempt.
 */
@Component
class WorkQueue {

    enum Table {
        PAYMENTS("payments"),
        REFUNDS("refunds");

        private final String sql;

        Table(String sql) {
            this.sql = sql;
        }
    }

    private final JdbcClient jdbc;

    WorkQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<UUID> due(Table table, Instant now, int limit) {
        return jdbc.sql("SELECT id FROM " + table.sql + """
                         WHERE next_attempt_at <= :now AND (lease_until IS NULL OR lease_until < :now)
                         ORDER BY next_attempt_at LIMIT :limit
                        """)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    /** True if this worker now holds the lease (until {@code until}); false if it is not due or taken. */
    boolean claim(Table table, UUID id, Instant now, Instant until) {
        return jdbc.sql("UPDATE " + table.sql + """
                         SET lease_until = :until, attempts = attempts + 1
                         WHERE id = :id AND next_attempt_at <= :now AND (lease_until IS NULL OR lease_until < :now)
                        """)
                .param("id", id)
                .param("now", Timestamp.from(now))
                .param("until", Timestamp.from(until))
                .update() == 1;
    }

    void release(Table table, UUID id) {
        jdbc.sql("UPDATE " + table.sql + " SET lease_until = NULL WHERE id = :id").param("id", id).update();
    }
}
