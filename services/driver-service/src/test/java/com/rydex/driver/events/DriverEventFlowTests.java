package com.rydex.driver.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.rydex.common.RideType;
import com.rydex.common.geo.GeoPoint;
import com.rydex.driver.IntegrationTest;
import com.rydex.driver.KafkaProbe;
import com.rydex.events.EventEnvelope;
import com.rydex.events.EventType;
import com.rydex.events.RidePayload;
import com.rydex.events.Topics;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** driver-service's side of the event flow, over a real Kafka broker. */
class DriverEventFlowTests extends IntegrationTest {

    static final Duration KAFKA_WAIT = Duration.ofSeconds(30);

    @Autowired
    JsonMapper json;

    /** What ride-service publishes for a booking / cancel / completion. */
    private EventEnvelope<RidePayload> rideEvent(EventType type, UUID rideId, RideType rideType, GeoPoint pickup) {
        var payload = new RidePayload(UUID.randomUUID(), null, rideType, pickup, AIRPORT,
                new BigDecimal("339.00"), 22111, 1359, null);
        return EventEnvelope.of(type, rideId.toString(), payload);
    }

    private void publish(EventEnvelope<?> event) {
        kafka().publish(Topics.RIDE_EVENTS, event.rideId(), json.writeValueAsString(event), event.eventType().name());
    }

    private void awaitOffer(TestDriver d, UUID rideId) {
        await().atMost(KAFKA_WAIT).untilAsserted(() -> {
            var res = currentOffer(d);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(res.getBody().path("rideId").asString()).isEqualTo(rideId.toString());
        });
    }

    private String jobStatus(UUID rideId) {
        return jdbc.sql("SELECT status FROM match_jobs WHERE ride_id = :id").param("id", rideId)
                .query(String.class).optional().orElse(null);
    }

    @Test
    void rideRequestedOnKafkaEndsWithDriverAssignedOnKafka() {
        TestDriver d = onlineDriver(RideType.ECONOMY, north(CENTER, 400));
        UUID rideId = UUID.randomUUID();

        publish(rideEvent(EventType.RIDE_REQUESTED, rideId, RideType.ECONOMY, CENTER));
        awaitOffer(d, rideId);
        assertThat(api().post("/api/drivers/me/offers/" + rideId + "/accept", d.token(), null)
                .getStatusCode().value()).isEqualTo(200);

        var assigned = kafka().await(Topics.RIDE_EVENTS, r -> rideId.toString().equals(r.key())
                && "DRIVER_ASSIGNED".equals(KafkaProbe.header(r, "eventType")), 1, KAFKA_WAIT);
        JsonNode event = json.readTree(assigned.get(0).value());
        assertThat(event.path("payload").path("driverId").asString()).isEqualTo(d.id().toString());
        assertThat(event.path("payload").path("rideType").asString()).isEqualTo("ECONOMY");
        assertThat(event.path("payload").path("fare").decimalValue()).isEqualByComparingTo("339");
    }

    @Test
    void nobodyNearbyEndsWithNoDriverFoundOnKafka() {
        UUID rideId = UUID.randomUUID();

        publish(rideEvent(EventType.RIDE_REQUESTED, rideId, RideType.XL, CENTER));

        var none = kafka().await(Topics.RIDE_EVENTS, r -> rideId.toString().equals(r.key())
                && "NO_DRIVER_FOUND".equals(KafkaProbe.header(r, "eventType")), 1, KAFKA_WAIT);
        assertThat(json.readTree(none.get(0).value()).path("payload").path("reason").asString())
                .isEqualTo("No drivers available nearby");
    }

    @Test
    void cancellationOnKafkaWithdrawsTheOffer() {
        TestDriver d = onlineDriver(RideType.COMFORT, north(CENTER, 300));
        UUID rideId = UUID.randomUUID();
        publish(rideEvent(EventType.RIDE_REQUESTED, rideId, RideType.COMFORT, CENTER));
        awaitOffer(d, rideId);

        publish(rideEvent(EventType.RIDE_CANCELLED, rideId, RideType.COMFORT, CENTER));

        await().atMost(KAFKA_WAIT).until(() -> "CANCELLED".equals(jobStatus(rideId)));
        assertThat(currentOffer(d).getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void completionOnKafkaMakesTheDriverAvailableAgain() {
        TestDriver d = onlineDriver(RideType.ECONOMY, north(CENTER, 300));
        UUID rideId = UUID.randomUUID();
        publish(rideEvent(EventType.RIDE_REQUESTED, rideId, RideType.ECONOMY, CENTER));
        awaitOffer(d, rideId);
        api().post("/api/drivers/me/offers/" + rideId + "/accept", d.token(), null);
        assertThat(api().get("/api/drivers/me", d.token()).getBody().path("status").asString()).isEqualTo("ON_TRIP");

        publish(rideEvent(EventType.RIDE_COMPLETED, rideId, RideType.ECONOMY, CENTER));

        await().atMost(KAFKA_WAIT).untilAsserted(() -> assertThat(
                api().get("/api/drivers/me", d.token()).getBody().path("status").asString()).isEqualTo("AVAILABLE"));
    }

    @Test
    void redeliveredEventsAreProcessedOnce() {
        UUID rideId = UUID.randomUUID();
        var requested = rideEvent(EventType.RIDE_REQUESTED, rideId, RideType.ECONOMY, CENTER);

        publish(requested);
        publish(requested);

        await().atMost(KAFKA_WAIT).until(() -> jobStatus(rideId) != null);
        await().pollDelay(Duration.ofSeconds(1)).atMost(KAFKA_WAIT).until(() -> jdbc.sql(
                "SELECT count(*) FROM processed_events WHERE event_id = :id").param("id", requested.eventId())
                .query(Integer.class).single() == 1);
        assertThat(jdbc.sql("SELECT count(*) FROM match_jobs WHERE ride_id = :id").param("id", rideId)
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void rideRequestsWithoutRideDetailsAreDeadLettered() {
        UUID rideId = UUID.randomUUID();
        var broken = EventEnvelope.of(EventType.RIDE_REQUESTED, rideId.toString(),
                new RidePayload(UUID.randomUUID(), null, RideType.ECONOMY, null, null, null, null, null, null));

        publish(broken);

        var dead = kafka().await(Topics.deadLetter(Topics.RIDE_EVENTS), r -> rideId.toString().equals(r.key()), 1, KAFKA_WAIT);
        assertThat(KafkaProbe.header(dead.get(0), "kafka_dlt-original-consumer-group")).isEqualTo("driver-service");
        assertThat(jobStatus(rideId)).isNull();
    }

    @Test
    void otherServicesEventsAreIgnored() {
        UUID rideId = UUID.randomUUID();
        var started = rideEvent(EventType.RIDE_STARTED, rideId, RideType.ECONOMY, CENTER);

        publish(started);

        // no marker and no job: the record was skipped on its eventType header without parsing
        await().pollDelay(Duration.ofSeconds(2)).atMost(KAFKA_WAIT).until(() -> true);
        assertThat(jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id = :id").param("id", started.eventId())
                .query(Integer.class).single()).isZero();
        assertThat(jobStatus(rideId)).isNull();
    }
}
