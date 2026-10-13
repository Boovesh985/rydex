package com.rydex.tracking.location;

import static com.rydex.tracking.StompTestClient.next;
import static com.rydex.tracking.StompTestClient.quiet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.rydex.common.DriverStatus;
import com.rydex.common.RedisKeys;
import com.rydex.common.RideType;
import com.rydex.common.ws.WsDestinations;
import com.rydex.events.DriverLocationEvent;
import com.rydex.events.EventType;
import com.rydex.events.Topics;
import com.rydex.presence.PresenceStore;
import com.rydex.tracking.IntegrationTest;
import com.rydex.tracking.KafkaProbe;
import com.rydex.tracking.StompTestClient;
import com.rydex.tracking.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Phase 4's "done when": the rider receives live driver updates. */
class LiveTrackingTests extends IntegrationTest {

    @Autowired
    PresenceStore presence;

    @Autowired
    LocationHistory history;

    /** A ride whose driver has been assigned, as seen by tracking-service. */
    private TestRide assignedRide() {
        TestRide ride = newRide();
        publish(EventType.RIDE_REQUESTED, ride);
        publish(EventType.DRIVER_ASSIGNED, ride);
        await().atMost(WAIT).until(() -> "DRIVER_ASSIGNED".equals(rideStatus(ride.id())));
        return ride;
    }

    private static Map<String, Object> at(double lat, double lng) {
        return Map.of("lat", lat, "lng", lng, "heading", 90.0, "speed", 8.5, "ts", System.currentTimeMillis());
    }

    @Test
    void riderSeesTheirDriverMoveWithALiveEta() throws Exception {
        TestRide ride = assignedRide();
        try (StompTestClient rider = connect(TestTokens.rider(ride.riderId()));
             StompTestClient driver = connect(TestTokens.driver(ride.driverId()))) {
            var live = rider.subscribe(WsDestinations.rideLocation(ride.id()));
            var driverErrors = driver.subscribe("/user/queue/errors");

            driver.send("/app/driver.location", at(13.0900, 80.2707)); // ~810 m north of the pickup

            JsonNode update = next(live);
            assertThat(update.path("rideId").asString()).isEqualTo(ride.id().toString());
            assertThat(update.path("driverId").asString()).isEqualTo(ride.driverId().toString());
            assertThat(update.path("lat").asDouble()).isEqualTo(13.0900);
            assertThat(update.path("heading").asDouble()).isEqualTo(90.0);
            assertThat(update.path("phase").asString()).isEqualTo("TO_PICKUP");
            // broadcast at once with the straight-line estimate (~810 m x 1.35 at 22 km/h) ...
            assertThat(update.path("etaEstimated").asBoolean()).isTrue();
            assertThat(update.path("etaS").asInt()).isBetween(170, 185);
            assertThat(quiet(driverErrors, Duration.ofMillis(200))).isTrue();

            // ... while OSRM is asked in the background; the next update carries its answer
            await().atMost(WAIT).until(() -> redis.hasKey("tracking:eta:" + ride.id() + ":TO_PICKUP"));
            Thread.sleep(600); // past the 2-per-second limit
            driver.send("/app/driver.location", at(13.0899, 80.2707));
            JsonNode next = next(live);
            assertThat(next.path("etaEstimated").asBoolean()).isFalse();
            assertThat(next.path("etaS").asInt()).isBetween(95, 105); // mocked OSRM: 8 m/s over ~810 m
            assertThat(next.path("distanceM").asInt()).isBetween(780, 830);

            // the update also reached Redis (as a heartbeat) and Kafka (for the AI and the history)
            assertThat(presence.location(ride.driverId())).get().satisfies(l -> assertThat(l.lat()).isEqualTo(13.0899));
            var records = kafka().await(Topics.DRIVER_LOCATION, r -> ride.driverId().toString().equals(r.key()), 1, WAIT);
            assertThat(KafkaProbe.header(records.get(0), "eventType")).isEqualTo("DRIVER_LOCATION");
            DriverLocationEvent event = json.readValue(records.get(0).value(), DriverLocationEvent.class);
            assertThat(event.rideId()).isEqualTo(ride.id());
            assertThat(event.status()).isEqualTo(DriverStatus.ON_TRIP);
            assertThat(event.geohash()).hasSize(6);

            // a screen opened mid-ride reads the same over REST
            var rest = api().get("/api/tracking/rides/" + ride.id() + "/location", TestTokens.rider(ride.riderId()));
            assertThat(rest.getStatusCode().value()).isEqualTo(200);
            assertThat(rest.getBody().path("lat").asDouble()).isEqualTo(13.0899);
            assertThat(rest.getBody().path("phase").asString()).isEqualTo("TO_PICKUP");
            assertThat(api().get("/api/tracking/rides/" + ride.id() + "/location", TestTokens.rider(UUID.randomUUID()))
                    .getStatusCode().value()).isEqualTo(404);
        }
    }

    @Test
    void theEtaTargetsTheDropOnceTheTripStarts() throws Exception {
        TestRide ride = assignedRide();
        publish(EventType.DRIVER_ARRIVED, ride);
        await().atMost(WAIT).until(() -> "DRIVER_ARRIVED".equals(rideStatus(ride.id())));
        try (StompTestClient rider = connect(TestTokens.rider(ride.riderId()));
             StompTestClient driver = connect(TestTokens.driver(ride.driverId()))) {
            var live = rider.subscribe(WsDestinations.rideLocation(ride.id()));

            driver.send("/app/driver.location", at(CENTER.lat(), CENTER.lng()));
            JsonNode atPickup = next(live);
            assertThat(atPickup.path("phase").asString()).isEqualTo("AT_PICKUP");
            assertThat(atPickup.path("etaS").asInt()).isZero();

            publish(EventType.RIDE_STARTED, ride);
            await().atMost(WAIT).until(() -> "IN_PROGRESS".equals(rideStatus(ride.id())));
            Thread.sleep(600); // past the 2-per-second limit
            driver.send("/app/driver.location", at(13.0700, 80.2600));
            JsonNode toDrop = next(live);
            assertThat(toDrop.path("phase").asString()).isEqualTo("TO_DROP");
            assertThat(toDrop.path("distanceM").asInt()).isGreaterThan(10_000); // the airport is ~15 km away
        }
    }

    @Test
    void whenOsrmIsDownTheEtaIsAStraightLineEstimate() {
        doThrow(new IllegalStateException("OSRM down")).when(osrm).route(any(), any());
        TestRide ride = assignedRide();
        try (StompTestClient rider = connect(TestTokens.rider(ride.riderId()));
             StompTestClient driver = connect(TestTokens.driver(ride.driverId()))) {
            var live = rider.subscribe(WsDestinations.rideLocation(ride.id()));

            driver.send("/app/driver.location", at(13.0900, 80.2707));

            JsonNode update = next(live);
            assertThat(update.path("etaEstimated").asBoolean()).isTrue();
            // ~810 m x 1.35 road factor at 22 km/h
            assertThat(update.path("etaS").asInt()).isBetween(170, 185);
        }
    }

    @Test
    void anAvailableDriversLocationFeedsTheMatchingIndexButIsBroadcastToNobody() {
        UUID driverId = UUID.randomUUID();
        presence.setStatus(driverId, DriverStatus.AVAILABLE, RideType.COMFORT); // driver-service did this on "go online"
        try (StompTestClient driver = connect(TestTokens.driver(driverId))) {
            driver.send("/app/driver.location", at(CENTER.lat(), CENTER.lng()));

            await().atMost(WAIT).untilAsserted(() -> assertThat(
                    redis.opsForZSet().score(RedisKeys.driversGeo(RideType.COMFORT), driverId.toString())).isNotNull());
            var records = kafka().await(Topics.DRIVER_LOCATION, r -> driverId.toString().equals(r.key()), 1, WAIT);
            DriverLocationEvent event = json.readValue(records.get(0).value(), DriverLocationEvent.class);
            assertThat(event.rideId()).isNull();
            assertThat(event.status()).isEqualTo(DriverStatus.AVAILABLE);
        }
    }

    @Test
    void updatesFasterThanTwoPerSecondOrInvalidAreRefused() throws Exception {
        UUID driverId = UUID.randomUUID();
        try (StompTestClient driver = connect(TestTokens.driver(driverId))) {
            var errors = driver.subscribe("/user/queue/errors");
            // the very first update can take longer than 500 ms (Kafka fetches metadata), so warm up first
            driver.send("/app/driver.location", at(13.08, 80.27));
            await().atMost(WAIT).until(() -> presence.location(driverId).isPresent());
            Thread.sleep(600);

            driver.send("/app/driver.location", at(13.0801, 80.27));
            driver.send("/app/driver.location", at(13.0802, 80.27));
            assertThat(next(errors).path("code").asString()).isEqualTo("RATE_LIMITED");

            driver.send("/app/driver.location", Map.of("lng", 80.27));
            assertThat(next(errors).path("code").asString()).isEqualTo("INVALID_LOCATION");
            driver.send("/app/driver.location", Map.of("lat", 13.08, "lng", 80.27, "speed", 500.0));
            assertThat(next(errors).path("message").asString()).contains("speed");
            assertThat(driver.isConnected()).isTrue();
        }
    }

    @Test
    void tripPathKeepsOnePointEveryFiveSecondsWhileOnARide() {
        TestRide ride = assignedRide();
        Instant t = Instant.now().minusSeconds(60);
        UUID otherDriver = UUID.randomUUID();

        assertThat(history.record(location(ride.driverId(), ride.id(), t))).isTrue();
        assertThat(history.record(location(ride.driverId(), ride.id(), t.plusSeconds(2)))).isFalse(); // within 5 s
        assertThat(history.record(location(ride.driverId(), ride.id(), t.plusSeconds(6)))).isTrue();
        assertThat(history.record(location(ride.driverId(), ride.id(), t.plusSeconds(6)))).isFalse(); // redelivered
        assertThat(history.record(location(otherDriver, null, t))).isFalse(); // not on a ride

        var path = api().get("/api/tracking/rides/" + ride.id() + "/path", TestTokens.rider(ride.riderId()));
        assertThat(path.getStatusCode().value()).isEqualTo(200);
        assertThat(path.getBody()).hasSize(2);
        assertThat(path.getBody().get(1).path("speed").asDouble()).isEqualTo(8.5);
        assertThat(api().get("/api/tracking/rides/" + ride.id() + "/path", TestTokens.rider(UUID.randomUUID()))
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void locationsSentDuringATripEndUpInItsPath() {
        TestRide ride = assignedRide();
        try (StompTestClient driver = connect(TestTokens.driver(ride.driverId()))) {
            driver.send("/app/driver.location", at(13.0900, 80.2707));

            await().atMost(WAIT).until(() -> history.path(ride.id()).size() == 1);
            assertThat(history.path(ride.id()).get(0).lat()).isEqualTo(13.0900);
        }
    }

    private static DriverLocationEvent location(UUID driverId, UUID rideId, Instant at) {
        return new DriverLocationEvent(UUID.randomUUID(), driverId, rideId, 13.08, 80.27, 90.0, 8.5, "tf346t",
                DriverStatus.ON_TRIP, at);
    }
}
