package com.rydex.tracking.location;

import static org.assertj.core.api.Assertions.assertThat;

import com.rydex.tracking.IntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LocationHistoryRetentionTests extends IntegrationTest {

    @Autowired
    LocationHistoryRetention retention;

    /** Points one second apart for a driver of their own, ending {@code age} ago. */
    private UUID trip(int points, Duration age) {
        UUID driver = UUID.randomUUID();
        Instant last = Instant.now().minus(age);
        jdbc.sql("""
                INSERT INTO driver_location_history (driver_id, ride_id, lat, lng, recorded_at)
                SELECT :driver, :ride, 13.08, 80.27, CAST(:last AS timestamptz) - make_interval(secs => n)
                FROM generate_series(0, :points - 1) AS n
                """)
                .param("driver", driver)
                .param("ride", UUID.randomUUID())
                .param("last", Timestamp.from(last))
                .param("points", points)
                .update();
        return driver;
    }

    private long points(UUID driver) {
        return jdbc.sql("SELECT count(*) FROM driver_location_history WHERE driver_id = :d").param("d", driver)
                .query(Long.class).single();
    }

    @Test
    void tripPathsOlderThanTheRetentionAreDeletedInBatches() {
        UUID old = trip(LocationHistoryRetention.BATCH + 300, Duration.ofDays(31));
        UUID recent = trip(20, Duration.ofDays(29));
        UUID today = trip(5, Duration.ofMinutes(1));

        assertThat(retention.purge()).isGreaterThanOrEqualTo(LocationHistoryRetention.BATCH + 300);

        assertThat(points(old)).isZero();
        assertThat(points(recent)).isEqualTo(20);
        assertThat(points(today)).isEqualTo(5);
        assertThat(retention.purge()).isZero();
    }
}
