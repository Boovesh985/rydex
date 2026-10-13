package com.rydex.tracking.ride;

import static com.rydex.tracking.StompTestClient.next;
import static com.rydex.tracking.StompTestClient.quiet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.rydex.common.ws.WsDestinations;
import com.rydex.common.ws.WsPush;
import com.rydex.events.EventType;
import com.rydex.events.Topics;
import com.rydex.tracking.IntegrationTest;
import com.rydex.tracking.StompTestClient;
import com.rydex.tracking.StubServices;
import com.rydex.tracking.TestTokens;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Ride notifications, offer pushes and offer answers. */
class NotificationTests extends IntegrationTest {

    @Autowired
    RideProjection projection;

    @Test
    void everyRideStepNotifiesTheRiderAndTheDriverWhereItConcernsThem() {
        TestRide ride = newRide();
        try (StompTestClient rider = connect(TestTokens.rider(ride.riderId()));
             StompTestClient driver = connect(TestTokens.driver(ride.driverId()))) {
            var riderInbox = rider.subscribe("/user/queue/notifications");
            var driverInbox = driver.subscribe("/user/queue/notifications");

            publish(EventType.RIDE_REQUESTED, ride);
            assertThat(next(riderInbox).path("title").asString()).isEqualTo("Finding your driver");

            var assigned = publish(EventType.DRIVER_ASSIGNED, ride);
            JsonNode onTheWay = next(riderInbox);
            assertThat(onTheWay.path("type").asString()).isEqualTo("DRIVER_ASSIGNED");
            assertThat(onTheWay.path("status").asString()).isEqualTo("DRIVER_ASSIGNED");
            assertThat(onTheWay.path("driverId").asString()).isEqualTo(ride.driverId().toString());
            assertThat(onTheWay.path("eventId").asString()).isEqualTo(assigned.eventId().toString());
            assertThat(next(driverInbox).path("title").asString()).isEqualTo("Ride assigned");

            publish(assigned); // redelivery: no second notification
            publish(EventType.DRIVER_ARRIVED, ride);
            assertThat(next(riderInbox).path("type").asString()).isEqualTo("DRIVER_ARRIVED");
            publish(EventType.RIDE_STARTED, ride);
            assertThat(next(riderInbox).path("status").asString()).isEqualTo("IN_PROGRESS");
            publish(EventType.RIDE_COMPLETED, ride);
            JsonNode done = next(riderInbox);
            assertThat(done.path("status").asString()).isEqualTo("COMPLETED");
            assertThat(done.path("message").asString()).contains("Rs 339.00");
            assertThat(next(driverInbox).path("type").asString()).isEqualTo("RIDE_COMPLETED");

            assertThat(quiet(riderInbox, Duration.ofSeconds(1))).isTrue();
            assertThat(quiet(driverInbox, Duration.ZERO)).isTrue();
        }
    }

    @Test
    void cancellationsReachBothAndLateAssignmentsAreIgnored() {
        TestRide ride = newRide();
        try (StompTestClient rider = connect(TestTokens.rider(ride.riderId()))) {
            var inbox = rider.subscribe("/user/queue/notifications");
            publish(EventType.RIDE_REQUESTED, ride);
            next(inbox);

            publish(EventType.RIDE_CANCELLED, ride, "changed plans");
            JsonNode cancelled = next(inbox);
            assertThat(cancelled.path("status").asString()).isEqualTo("CANCELLED");
            assertThat(cancelled.path("message").asString()).isEqualTo("changed plans");

            // driver-service's assignment raced the cancellation: ride-service ignores it, and so do we
            var late = publish(EventType.DRIVER_ASSIGNED, ride);
            await().atMost(WAIT).until(() -> jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id = :id")
                    .param("id", late.eventId()).query(Integer.class).single() == 1);
            assertThat(rideStatus(ride.id())).isEqualTo("CANCELLED");
            assertThat(quiet(inbox, Duration.ofMillis(500))).isTrue();
        }
    }

    @Test
    void noDriverFoundTellsTheRiderWhy() {
        TestRide ride = newRide();
        try (StompTestClient rider = connect(TestTokens.rider(ride.riderId()))) {
            var inbox = rider.subscribe("/user/queue/notifications");
            publish(EventType.RIDE_REQUESTED, ride);
            next(inbox);

            publish(EventType.NO_DRIVER_FOUND, ride, "No drivers available nearby");

            JsonNode none = next(inbox);
            assertThat(none.path("title").asString()).isEqualTo("No drivers available");
            assertThat(none.path("message").asString()).startsWith("No drivers available nearby.");
        }
    }

    @Test
    void malformedRideEventsAreDeadLettered() {
        String key = "bad-" + UUID.randomUUID();
        kafka().publish(Topics.RIDE_EVENTS, key, "{not json", "RIDE_STARTED");

        var dead = kafka().await(Topics.deadLetter(Topics.RIDE_EVENTS), r -> key.equals(r.key())
                && "tracking-service".equals(com.rydex.tracking.KafkaProbe.header(r, "kafka_dlt-original-consumer-group")), 1, WAIT);
        assertThat(dead).hasSize(1);
    }

    @Test
    void offersPublishedByDriverServiceReachOnlyThatDriver() throws Exception {
        UUID driverId = UUID.randomUUID();
        UUID rideId = UUID.randomUUID();
        try (StompTestClient driver = connect(TestTokens.driver(driverId));
             StompTestClient otherDriver = connect(TestTokens.driver(UUID.randomUUID()))) {
            var offers = driver.subscribe("/user/queue/offers");
            var otherOffers = otherDriver.subscribe("/user/queue/offers");
            Thread.sleep(300); // let the subscriptions register

            // exactly what driver-service's OfferNotifier publishes
            redis.convertAndSend(WsPush.CHANNEL, json.writeValueAsString(WsPush.toUser(driverId, WsDestinations.OFFERS,
                    Map.of("type", "OFFER", "rideId", rideId.toString(), "offer", Map.of("secondsLeft", 15)))));

            JsonNode offer = next(offers);
            assertThat(offer.path("type").asString()).isEqualTo("OFFER");
            assertThat(offer.path("offer").path("secondsLeft").asInt()).isEqualTo(15);
            assertThat(quiet(otherOffers, Duration.ofMillis(500))).isTrue();
        }
    }

    @Test
    void offerAnswersAreForwardedToDriverServiceWithTheDriversToken() {
        UUID driverId = UUID.randomUUID();
        String token = TestTokens.driver(driverId);
        UUID accepted = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        StubServices.answerOffer(expired, 409, StubServices.problem(409, "This offer has expired"));
        try (StompTestClient driver = connect(token)) {
            var offers = driver.subscribe("/user/queue/offers");

            driver.send("/app/offer.respond", Map.of("rideId", accepted.toString(), "accept", true));
            JsonNode ok = next(offers);
            assertThat(ok.path("type").asString()).isEqualTo("OFFER_RESPONSE");
            assertThat(ok.path("ok").asBoolean()).isTrue();
            assertThat(ok.path("status").asInt()).isEqualTo(200);
            assertThat(ok.path("assignment").path("rideId").asString()).isEqualTo(accepted.toString());

            driver.send("/app/offer.respond", Map.of("rideId", expired.toString(), "accept", true));
            JsonNode late = next(offers);
            assertThat(late.path("ok").asBoolean()).isFalse();
            assertThat(late.path("status").asInt()).isEqualTo(409);
            assertThat(late.path("detail").asString()).isEqualTo("This offer has expired");

            driver.send("/app/offer.respond", Map.of("rideId", accepted.toString(), "accept", false));
            assertThat(next(offers).path("status").asInt()).isEqualTo(204);

            driver.send("/app/offer.respond", Map.of("accept", true));
            assertThat(next(offers).path("status").asInt()).isEqualTo(400);
        }
        List<StubServices.Received> calls = StubServices.received().stream()
                .filter(r -> r.path().contains(accepted.toString()) || r.path().contains(expired.toString())).toList();
        assertThat(calls).extracting(StubServices.Received::path).containsExactly(
                "/api/drivers/me/offers/" + accepted + "/accept",
                "/api/drivers/me/offers/" + expired + "/accept",
                "/api/drivers/me/offers/" + accepted + "/reject");
        assertThat(calls).allSatisfy(r -> assertThat(r.authorization()).isEqualTo("Bearer " + token));
    }

    @Test
    void theProjectionCanBeBuiltFromAnyFirstEvent() {
        TestRide ride = newRide();

        // RIDE_REQUESTED was consumed before this service existed; the next event still carries the ride
        var assigned = com.rydex.events.EventEnvelope.of(EventType.DRIVER_ASSIGNED, ride.id().toString(), ride.payload(null));
        assertThat(projection.apply(assigned)).isEqualTo(RideProjection.Result.APPLIED);
        assertThat(projection.apply(assigned)).isEqualTo(RideProjection.Result.DUPLICATE);

        assertThat(rideStatus(ride.id())).isEqualTo("DRIVER_ASSIGNED");
        assertThat(jdbc.sql("SELECT driver_id FROM tracked_rides WHERE ride_id = :id").param("id", ride.id())
                .query(UUID.class).single()).isEqualTo(ride.driverId());
    }
}
