package com.rydex.driver.matching;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Claims due jobs for this poller. The update moves {@code due_at} forward by the lease, so
 * other instances skip the job, and {@code SKIP LOCKED} keeps concurrent pollers from
 * blocking on each other. If the instance dies mid-step, the job is due again after the lease.
 */
@Component
class DueJobClaimer {

    private final JdbcClient jdbc;

    DueJobClaimer(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<UUID> claim(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                UPDATE match_jobs SET due_at = :leaseUntil, version = version + 1
                WHERE ride_id IN (
                    SELECT ride_id FROM match_jobs
                    WHERE status IN ('SEARCHING', 'OFFERED') AND due_at <= :now
                    ORDER BY due_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED)
                RETURNING ride_id
                """)
                .param("leaseUntil", Timestamp.from(leaseUntil))
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }
}
