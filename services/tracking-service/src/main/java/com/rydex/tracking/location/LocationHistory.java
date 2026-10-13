package com.rydex.tracking.location;

import com.rydex.events.DriverLocationEvent;
import com.rydex.events.Topics;
import com.rydex.messaging.NonRetryableEventException;
import com.rydex.tracking.config.TrackingProperties;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The trip path ({@code driver_location_history}, master prompt §5): consumes {@code driver.location}
 * as its own group and stores at most one point per driver per {@code historyInterval} (5 s), and
 * only while the driver is on a ride.
 *
 * <p>The sampling decision is part of the insert (no point within the interval before this one),
 * so it needs no extra state and a redelivered event changes nothing: (driver, time) is unique.
 */
@Component
public class LocationHistory {

    /** One stored point of a trip. */
    public record PathPoint(double lat, double lng, Double speed, Double heading, Instant at) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TrackingProperties props;

    LocationHistory(JdbcClient jdbc, JsonMapper json, TrackingProperties props) {
        this.jdbc = jdbc;
        this.json = json;
        this.props = props;
    }

    @KafkaListener(id = "tracking-service.location-history", idIsGroup = false, groupId = "tracking-history",
            topics = Topics.DRIVER_LOCATION)
    void onLocation(ConsumerRecord<String, String> record) {
        DriverLocationEvent event;
        try {
            event = json.readValue(record.value(), DriverLocationEvent.class);
        } catch (RuntimeException e) {
            throw new NonRetryableEventException("unreadable driver location: " + e.getMessage());
        }
        record(event);
    }

    /** Returns true if the point was stored. */
    boolean record(DriverLocationEvent e) {
        if (e.rideId() == null) {
            return false;
        }
        Timestamp at = Timestamp.from(e.recordedAt());
        return jdbc.sql("""
                INSERT INTO driver_location_history (driver_id, ride_id, lat, lng, speed, heading, recorded_at)
                SELECT CAST(:driverId AS uuid), CAST(:rideId AS uuid), CAST(:lat AS double precision),
                       CAST(:lng AS double precision), CAST(:speed AS double precision),
                       CAST(:heading AS double precision), CAST(:at AS timestamptz)
                WHERE NOT EXISTS (
                    SELECT 1 FROM driver_location_history
                    WHERE driver_id = CAST(:driverId AS uuid)
                      AND recorded_at > CAST(:at AS timestamptz) - make_interval(secs => CAST(:interval AS double precision))
                      AND recorded_at <= CAST(:at AS timestamptz))
                ON CONFLICT DO NOTHING
                """)
                .param("driverId", e.driverId())
                .param("rideId", e.rideId())
                .param("lat", e.lat())
                .param("lng", e.lng())
                .param("speed", e.speed())
                .param("heading", e.heading())
                .param("at", at)
                .param("interval", props.historyInterval().toMillis() / 1000.0)
                .update() == 1;
    }

    /** The stored path of a ride, oldest first. */
    public List<PathPoint> path(UUID rideId) {
        return jdbc.sql("""
                SELECT lat, lng, speed, heading, recorded_at FROM driver_location_history
                WHERE ride_id = :rideId ORDER BY recorded_at
                """)
                .param("rideId", rideId)
                .query((rs, i) -> new PathPoint(rs.getDouble("lat"), rs.getDouble("lng"),
                        (Double) rs.getObject("speed"), (Double) rs.getObject("heading"),
                        rs.getTimestamp("recorded_at").toInstant()))
                .list();
    }
}
