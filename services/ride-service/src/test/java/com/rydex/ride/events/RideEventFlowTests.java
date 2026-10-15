package com.rydex.ride.events;

import static com.rydex.ride.TestApi.token;
import static com.rydex.ride.TestApi.userId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.rydex.common.RideType;
import com.rydex.common.geo.GeoPoint;
import com.rydex.events.EventEnvelope;
import com.rydex.events.EventType;
import com.rydex.events.RidePayload;
import com.rydex.events.Topics;
import com.rydex.ride.IntegrationTest;
import com.rydex.ride.KafkaProbe;
import com.rydex.ride.geo.RouteResult;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** ride-service's side of the event flow, over a real Kafka broker. */
class RideEventFlowTests extends IntegrationTest {

    static final Map<String, Object> PICKUP = Map.of("lat", 13.0827, "lng", 80.2707, "address", "Chennai Central");
    static final Map<String, Object> DROP = Map.of("lat", 13.0500, "lng", 80.2824, "address", "Marina Beach");
    static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void stubRouting() {
        given(osrm.route(any(), any())).willReturn(List.of(new RouteResult.Alternative(5_000, 900, "poly")));
    }

    private record Booked(UUID rideId, String riderToken, String otp) {
    }

    private Booked book() {
        String rider = token(api().register("RIDER"));
        var res = api().call(HttpMethod.POST, "/api/rides", rider,
                Map.of("pickup", PICKUP, "drop", DROP, "rideType", "ECONOMY"),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(res.getStatusCode().value()).as("book: %s", res.getBody()).isEqualTo(201);
        return new Booked(UUID.fromString(res.getBody().path("id").asString()), rider, res.getBody().path("otp").asString());
    }

    /** What driver-service would publish for this ride. */
    private EventEnvelope<RidePayload> outcome(EventType type, UUID rideId, UUID driverId) {
        var payload = new RidePayload(UUID.randomUUID(), driverId, RideType.ECONOMY, GeoPoint.of(13.0827, 80.2707),
                GeoPoint.of(13.05, 80.2824), new BigDecimal("123.00"), 5000, 900, null);
        return EventEnvelope.of(type, rideId.toString(), payload);
    }

    private void publish(EventEnvelope<?> event) {
        kafka().publish(Topics.RIDE_EVENTS, event.rideId(), json.writeValueAsString(event), event.eventType().name());
    }

    private JsonNode ride(Booked b) {
        return api().get("/api/rides/" + b.rideId(), b.riderToken()).getBody();
    }

    private void awaitStatus(Booked b, String status) {
        await().atMost(WAIT).untilAsserted(() -> assertThat(ride(b).path("status").asString()).isEqualTo(status));
    }

    private int unpublished(Booked b) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE aggregate_id = :id AND published_at IS NULL")
                .param("id", b.rideId().toString()).query(Integer.class).single();
    }

    private int processed(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id = :id").param("id", eventId)
                .query(Integer.class).single();
    }

    @Test
    void bookingIsRelayedToKafkaKeyedByRide() {
        Booked b = book();

        var records = kafka().await(Topics.RIDE_EVENTS, r -> b.rideId().toString().equals(r.key()), 1, WAIT);

        var record = records.get(0);
        assertThat(KafkaProbe.header(record, "eventType")).isEqualTo("RIDE_REQUESTED");
        JsonNode event = json.readTree(record.value());
        assertThat(event.path("eventType").asString()).isEqualTo("RIDE_REQUESTED");
        assertThat(event.path("rideId").asString()).isEqualTo(b.rideId().toString());
        assertThat(event.path("payload").path("rideType").asString()).isEqualTo("ECONOMY");
        assertThat(event.path("payload").path("pickup").path("address").asString()).isEqualTo("Chennai Central");
        assertThat(KafkaProbe.header(record, "eventId")).isEqualTo(event.path("eventId").asString());
        await().atMost(WAIT).until(() -> jdbc.sql(
                "SELECT count(*) FROM outbox WHERE aggregate_id = :id AND published_at IS NULL")
                .param("id", b.rideId().toString()).query(Integer.class).single() == 0);
    }

    @Test
    void eventsOfOneRideArePublishedInOrder() {
        Booked b = book();
        api().post("/api/rides/" + b.rideId() + "/cancel", b.riderToken(), Map.of("reason", "changed plans"));

        var records = kafka().await(Topics.RIDE_EVENTS, r -> b.rideId().toString().equals(r.key()), 2, WAIT);

        assertThat(records).extracting(r -> KafkaProbe.header(r, "eventType"))
                .containsExactly("RIDE_REQUESTED", "RIDE_CANCELLED");
        assertThat(records.get(0).partition()).isEqualTo(records.get(1).partition());
    }

    @Test
    void onlyOneRelayPublishesAtATime() throws Exception {
        String lock = "hashtext(current_schema() || '.outbox-relay')";
        Booked b;
        try (Connection otherInstance = dataSource.getConnection(); Statement st = otherInstance.createStatement()) {
            // what another instance's relay holds during its pass
            st.execute("SELECT pg_advisory_lock(" + lock + ")");
            try {
                b = book();
                await().during(Duration.ofSeconds(1)).atMost(WAIT).until(() -> unpublished(b) == 1);
            } finally {
                st.execute("SELECT pg_advisory_unlock(" + lock + ")");
            }
        }

        await().atMost(WAIT).until(() -> unpublished(b) == 0);
    }

    @Test
    void driverAssignedFromKafkaAssignsTheRideOnce() {
        Booked b = book();
        var driverAuth = api().register("DRIVER");
        UUID driverId = userId(driverAuth);
        var assigned = outcome(EventType.DRIVER_ASSIGNED, b.rideId(), driverId);

        publish(assigned);
        awaitStatus(b, "DRIVER_ASSIGNED");
        publish(assigned); // redelivery of the same event
        await().pollDelay(Duration.ofSeconds(1)).atMost(WAIT).until(() -> processed(assigned.eventId()) == 1);

        JsonNode ride = ride(b);
        assertThat(ride.path("driverId").asString()).isEqualTo(driverId.toString());
        assertThat(jdbc.sql("SELECT count(*) FROM ride_status_history WHERE ride_id = :id AND to_status = 'DRIVER_ASSIGNED'")
                .param("id", b.rideId()).query(Integer.class).single()).isEqualTo(1);

        // the assigned driver can now run the trip; completion goes back out on Kafka
        String driver = token(driverAuth);
        driverAtPickup(b.rideId());
        assertThat(api().post("/api/rides/" + b.rideId() + "/arrived", driver, null).getStatusCode().value()).isEqualTo(200);
        assertThat(api().post("/api/rides/" + b.rideId() + "/start", driver, Map.of("otp", b.otp())).getStatusCode().value()).isEqualTo(200);
        assertThat(api().post("/api/rides/" + b.rideId() + "/complete", driver, null).getStatusCode().value()).isEqualTo(200);
        var completed = kafka().await(Topics.RIDE_EVENTS, r -> b.rideId().toString().equals(r.key())
                && "RIDE_COMPLETED".equals(KafkaProbe.header(r, "eventType")), 1, WAIT);
        assertThat(json.readTree(completed.get(0).value()).path("payload").path("driverId").asString())
                .isEqualTo(driverId.toString());
    }

    @Test
    void noDriverFoundEndsTheRequest() {
        Booked b = book();

        publish(outcome(EventType.NO_DRIVER_FOUND, b.rideId(), null));

        awaitStatus(b, "NO_DRIVER_FOUND");
        assertThat(ride(b).path("otp").isNull()).isTrue();
        // no longer an active ride, so the rider can book again straight away
        var again = api().call(HttpMethod.POST, "/api/rides", b.riderToken(),
                Map.of("pickup", PICKUP, "drop", DROP, "rideType", "COMFORT"),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(again.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void assignmentForACancelledRideIsIgnored() {
        Booked b = book();
        api().post("/api/rides/" + b.rideId() + "/cancel", b.riderToken(), Map.of("reason", "too slow"));
        var late = outcome(EventType.DRIVER_ASSIGNED, b.rideId(), UUID.randomUUID());

        publish(late);

        await().atMost(WAIT).until(() -> processed(late.eventId()) == 1);
        assertThat(ride(b).path("status").asString()).isEqualTo("CANCELLED");
        assertThat(ride(b).path("driverId").isNull()).isTrue();
    }

    @Test
    void malformedEventsGoToTheDeadLetterTopic() {
        String garbageKey = "garbage-" + UUID.randomUUID();

        kafka().publish(Topics.RIDE_EVENTS, garbageKey, "{not json", "DRIVER_ASSIGNED");
        // a ride id we know nothing about would be ignored, so book one to reach the driverId check
        Booked b = book();
        var missingDriver = outcome(EventType.DRIVER_ASSIGNED, b.rideId(), null);
        publish(missingDriver);

        var dead = kafka().await(Topics.deadLetter(Topics.RIDE_EVENTS),
                r -> garbageKey.equals(r.key()) || b.rideId().toString().equals(r.key()), 2, WAIT);
        assertThat(dead).extracting(r -> r.key()).containsExactlyInAnyOrder(garbageKey, b.rideId().toString());
        for (var record : dead) {
            assertThat(KafkaProbe.header(record, "kafka_dlt-exception-fqcn") + " "
                    + KafkaProbe.header(record, "kafka_dlt-exception-cause-fqcn")).contains("NonRetryableEventException");
            assertThat(KafkaProbe.header(record, "kafka_dlt-original-consumer-group")).isEqualTo("ride-service");
        }
        assertThat(ride(b).path("status").asString()).isEqualTo("REQUESTED");
    }
}
