package com.rydex.driver.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.rydex.common.RedisKeys;
import com.rydex.common.ws.WsPush;
import com.rydex.common.RideType;
import com.rydex.common.geo.GeoPoint;
import com.rydex.driver.IntegrationTest;
import com.rydex.driver.TestTokens;
import com.rydex.events.RidePayload;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MatchingFlowTests extends IntegrationTest {

    @Autowired
    DriverLocks locks;

    @Autowired
    JsonMapper json;

    private String jobStatus(UUID rideId) {
        return jdbc.sql("SELECT status FROM match_jobs WHERE ride_id = :id").param("id", rideId).query(String.class).single();
    }

    private void awaitOfferTo(TestDriver d, UUID rideId) {
        await().atMost(WAIT).untilAsserted(() -> {
            var res = currentOffer(d);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(res.getBody().path("rideId").asString()).isEqualTo(rideId.toString());
        });
    }

    private void assertNoOffer(TestDriver d) {
        assertThat(currentOffer(d).getStatusCode().value()).isEqualTo(204);
    }

    private void awaitJobStatus(UUID rideId, String status) {
        await().atMost(WAIT).untilAsserted(() -> assertThat(jobStatus(rideId)).isEqualTo(status));
    }

    @Test
    void nearestAvailableDriverOfTheRightTypeGetsTheRideOnAccept() {
        TestDriver near = onlineDriver(RideType.ECONOMY, north(CENTER, 500));
        TestDriver mid = onlineDriver(RideType.ECONOMY, north(CENTER, 1500));
        TestDriver far = onlineDriver(RideType.ECONOMY, north(CENTER, 2500));
        TestDriver comfortCloser = onlineDriver(RideType.COMFORT, north(CENTER, 200));
        TestDriver offlineCloser = newDriver(RideType.ECONOMY); // never goes online

        UUID rideId = requestRide(RideType.ECONOMY, CENTER);

        awaitOfferTo(near, rideId);
        var offer = currentOffer(near).getBody();
        assertThat(offer.path("rideType").asString()).isEqualTo("ECONOMY");
        assertThat(offer.path("distanceToPickupM").asInt()).isBetween(450, 550);
        assertThat(offer.path("pickupEtaS").asInt()).isPositive();
        assertThat(offer.path("estFare").decimalValue()).isEqualByComparingTo("339");
        assertThat(offer.path("secondsLeft").asLong()).isBetween(0L, 2L);
        assertThat(offer.path("drop").path("address").asString()).isEqualTo("Chennai Airport");
        for (TestDriver other : List.of(mid, far, comfortCloser, offlineCloser)) {
            assertNoOffer(other);
        }
        assertThat(locks.holder(near.id())).isEqualTo(rideId.toString());
        assertThat(redis.opsForHash().get(RedisKeys.rideOffer(rideId), "driverId")).isEqualTo(near.id().toString());

        var accepted = api().post("/api/drivers/me/offers/" + rideId + "/accept", near.token(), null);

        assertThat(accepted.getStatusCode().value()).isEqualTo(200);
        assertThat(accepted.getBody().path("driverId").asString()).isEqualTo(near.id().toString());
        assertThat(jobStatus(rideId)).isEqualTo("ASSIGNED");
        assertThat(outboxEvents(rideId)).containsExactly("DRIVER_ASSIGNED");
        assertThat(outboxField(rideId, "DRIVER_ASSIGNED", "driverId")).isEqualTo(near.id().toString());
        assertThat(outboxField(rideId, "DRIVER_ASSIGNED", "rideType")).isEqualTo("ECONOMY");

        var me = api().get("/api/drivers/me", near.token()).getBody();
        assertThat(me.path("status").asString()).isEqualTo("ON_TRIP");
        assertThat(me.path("currentRideId").asString()).isEqualTo(rideId.toString());
        assertThat(redis.opsForZSet().score(RedisKeys.driversGeo(RideType.ECONOMY), near.id().toString())).isNull();
        assertThat(locks.holder(near.id())).isNull();
        assertThat(redis.hasKey(RedisKeys.rideOffer(rideId))).isFalse();
        assertNoOffer(near);
    }

    @Test
    void rejectionPassesTheRideToTheNextNearestDriver() {
        TestDriver first = onlineDriver(RideType.COMFORT, north(CENTER, 300));
        TestDriver second = onlineDriver(RideType.COMFORT, north(CENTER, 900));
        UUID rideId = requestRide(RideType.COMFORT, CENTER);
        awaitOfferTo(first, rideId);

        var rejected = api().post("/api/drivers/me/offers/" + rideId + "/reject", first.token(), null);

        assertThat(rejected.getStatusCode().value()).isEqualTo(204);
        assertThat(locks.holder(first.id())).isNull();
        awaitOfferTo(second, rideId);
        assertNoOffer(first);
        assertThat(api().post("/api/drivers/me/offers/" + rideId + "/accept", second.token(), null)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(outboxField(rideId, "DRIVER_ASSIGNED", "driverId")).isEqualTo(second.id().toString());
    }

    @Test
    void unansweredOffersMoveOnAndEndInNoDriverFoundAfterMaxAttempts() {
        TestDriver a = onlineDriver(RideType.XL, north(CENTER, 200));
        TestDriver b = onlineDriver(RideType.XL, north(CENTER, 400));
        TestDriver c = onlineDriver(RideType.XL, north(CENTER, 600));
        TestDriver d = onlineDriver(RideType.XL, north(CENTER, 800));
        UUID rideId = requestRide(RideType.XL, CENTER);

        awaitOfferTo(a, rideId);
        awaitOfferTo(b, rideId);
        awaitOfferTo(c, rideId);
        awaitJobStatus(rideId, "NO_DRIVER_FOUND");

        assertNoOffer(d); // max-attempts = 3 in tests
        assertThat(outboxEvents(rideId)).containsExactly("NO_DRIVER_FOUND");
        assertThat(outboxField(rideId, "NO_DRIVER_FOUND", "reason")).contains("after 3 offers");
        var job = api().get("/api/admin/matching/" + rideId, TestTokens.admin()).getBody();
        assertThat(job.path("offers")).hasSize(3);
        assertThat(job.path("offers").get(0).path("outcome").asString()).isEqualTo("EXPIRED");
        for (TestDriver t : List.of(a, b, c)) {
            assertThat(locks.holder(t.id())).isNull();
        }
    }

    @Test
    void noDriverWithinEightKilometresMeansNoDriverFound() {
        onlineDriver(RideType.ECONOMY, north(CENTER, 9_000));
        UUID rideId = requestRide(RideType.ECONOMY, CENTER);

        awaitJobStatus(rideId, "NO_DRIVER_FOUND");

        assertThat(outboxField(rideId, "NO_DRIVER_FOUND", "reason")).isEqualTo("No drivers available nearby");
        assertThat(api().get("/api/admin/matching/" + rideId, TestTokens.admin()).getBody().path("offers")).isEmpty();
    }

    @Test
    void driverWhoComesOnlineDuringTheSearchGetsTheOffer() {
        UUID rideId = requestRide(RideType.ECONOMY, CENTER);
        await().pollDelay(Duration.ofMillis(500)).atMost(WAIT)
                .untilAsserted(() -> assertThat(jobStatus(rideId)).isEqualTo("SEARCHING"));

        TestDriver late = onlineDriver(RideType.ECONOMY, north(CENTER, 1000));

        awaitOfferTo(late, rideId);
    }

    @Test
    void searchWidensTheRadiusUntilSomeoneIsFound() {
        TestDriver sixKmAway = onlineDriver(RideType.COMFORT, north(CENTER, 6_000));
        UUID rideId = requestRide(RideType.COMFORT, CENTER);

        awaitOfferTo(sixKmAway, rideId);
    }

    @Test
    void shorterDrivingEtaBeatsShorterStraightLineDistance() {
        TestDriver acrossTheRiver = onlineDriver(RideType.ECONOMY, north(CENTER, 400));
        TestDriver onTheSameRoad = onlineDriver(RideType.ECONOMY, north(CENTER, 1200));
        GeoPoint riverSide = north(CENTER, 400);
        doAnswer(inv -> {
            List<GeoPoint> sources = inv.getArgument(0);
            // the closer driver needs a 15-minute detour to cross the river
            return sources.stream().map(s -> s.distanceMetersTo(riverSide) < 50 ? 900.0 : 180.0).toArray(Double[]::new);
        }).when(osrm).durationsTo(any(), any());

        UUID rideId = requestRide(RideType.ECONOMY, CENTER);

        awaitOfferTo(onTheSameRoad, rideId);
        assertNoOffer(acrossTheRiver);
        assertThat(currentOffer(onTheSameRoad).getBody().path("pickupEtaS").asInt()).isEqualTo(180);
    }

    @Test
    void routingOutageFallsBackToStraightLineEtas() {
        TestDriver near = onlineDriver(RideType.ECONOMY, north(CENTER, 700));
        onlineDriver(RideType.ECONOMY, north(CENTER, 2000));
        doThrow(new ResourceAccessException("OSRM down")).when(osrm).durationsTo(any(), any());

        UUID rideId = requestRide(RideType.ECONOMY, CENTER);

        awaitOfferTo(near, rideId);
        // 700 m x 1.35 road factor at 22 km/h
        assertThat(currentOffer(near).getBody().path("pickupEtaS").asInt()).isBetween(150, 160);
    }

    @Test
    void aDriverIsNeverOfferedTwoRidesAtOnce() {
        TestDriver only = onlineDriver(RideType.ECONOMY, north(CENTER, 500));
        UUID ride1 = requestRide(RideType.ECONOMY, CENTER);
        UUID ride2 = requestRide(RideType.ECONOMY, north(CENTER, 100));

        await().atMost(WAIT).until(() -> currentOffer(only).getStatusCode().value() == 200);
        String offered = currentOffer(only).getBody().path("rideId").asString();
        UUID waiting = offered.equals(ride1.toString()) ? ride2 : ride1;

        assertThat(jobStatus(waiting)).isEqualTo("SEARCHING");
        assertThat(jdbc.sql("SELECT count(*) FROM match_offers WHERE driver_id = :d").param("d", only.id())
                .query(Integer.class).single()).isEqualTo(1);

        // once the driver turns the first ride down, the other ride can have them
        api().post("/api/drivers/me/offers/" + offered + "/reject", only.token(), null);
        awaitOfferTo(only, waiting);
    }

    @Test
    void goingOfflineTurnsDownThePendingOffer() {
        TestDriver leaving = onlineDriver(RideType.ECONOMY, north(CENTER, 300));
        TestDriver next = onlineDriver(RideType.ECONOMY, north(CENTER, 800));
        UUID rideId = requestRide(RideType.ECONOMY, CENTER);
        awaitOfferTo(leaving, rideId);

        api().patch("/api/drivers/me/status", leaving.token(), Map.of("status", "OFFLINE"));

        awaitOfferTo(next, rideId);
        assertThat(jdbc.sql("SELECT outcome FROM match_offers WHERE ride_id = :r AND driver_id = :d")
                .param("r", rideId).param("d", leaving.id()).query(String.class).single()).isEqualTo("REJECTED");
    }

    @Test
    void onlyTheOfferedDriverCanAcceptAndOnlyInTime() {
        TestDriver offered = onlineDriver(RideType.ECONOMY, north(CENTER, 300));
        TestDriver other = onlineDriver(RideType.ECONOMY, north(CENTER, 5000));
        UUID rideId = requestRide(RideType.ECONOMY, CENTER);
        awaitOfferTo(offered, rideId);

        assertThat(api().post("/api/drivers/me/offers/" + rideId + "/accept", other.token(), null)
                .getStatusCode().value()).isEqualTo(404);
        assertThat(api().post("/api/drivers/me/offers/" + rideId + "/accept", TestTokens.rider(UUID.randomUUID()), null)
                .getStatusCode().value()).isEqualTo(403);
        assertThat(api().post("/api/drivers/me/offers/" + UUID.randomUUID() + "/accept", offered.token(), null)
                .getStatusCode().value()).isEqualTo(404);

        // let the 2 s offer lapse: the scheduler expires it (404) or it is caught as expired (409)
        await().atMost(WAIT).until(() -> currentOffer(offered).getStatusCode().value() == 204);
        int late = api().post("/api/drivers/me/offers/" + rideId + "/accept", offered.token(), null).getStatusCode().value();
        assertThat(late).isIn(404, 409);
    }

    @Test
    void cancellingDuringAnOfferStopsMatchingAndFreesTheDriver() {
        TestDriver d = onlineDriver(RideType.ECONOMY, north(CENTER, 300));
        UUID rideId = requestRide(RideType.ECONOMY, CENTER);
        awaitOfferTo(d, rideId);

        matching.onRideCancelled(rideId);

        assertThat(jobStatus(rideId)).isEqualTo("CANCELLED");
        assertNoOffer(d);
        assertThat(locks.holder(d.id())).isNull();
        assertThat(redis.hasKey(RedisKeys.rideOffer(rideId))).isFalse();
        assertThat(outboxEvents(rideId)).isEmpty();
        assertThat(api().post("/api/drivers/me/offers/" + rideId + "/accept", d.token(), null)
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void cancellingOrCompletingAnAssignedRideMakesTheDriverAvailableAgain() {
        TestDriver d = onlineDriver(RideType.COMFORT, north(CENTER, 300));
        UUID cancelled = requestRide(RideType.COMFORT, CENTER);
        awaitOfferTo(d, cancelled);
        api().post("/api/drivers/me/offers/" + cancelled + "/accept", d.token(), null);

        matching.onRideCancelled(cancelled);
        matching.onRideCancelled(cancelled); // duplicate delivery is harmless

        assertThat(api().get("/api/drivers/me", d.token()).getBody().path("status").asString()).isEqualTo("AVAILABLE");
        assertThat(redis.opsForZSet().score(RedisKeys.driversGeo(RideType.COMFORT), d.id().toString())).isNotNull();

        UUID completed = requestRide(RideType.COMFORT, CENTER);
        awaitOfferTo(d, completed);
        api().post("/api/drivers/me/offers/" + completed + "/accept", d.token(), null);
        assertThat(api().get("/api/drivers/me", d.token()).getBody().path("status").asString()).isEqualTo("ON_TRIP");
        assertThat(api().patch("/api/drivers/me/status", d.token(), Map.of("status", "OFFLINE"))
                .getStatusCode().value()).isEqualTo(409); // cannot leave mid-trip

        matching.onRideCompleted(completed);

        assertThat(api().get("/api/drivers/me", d.token()).getBody().path("status").asString()).isEqualTo("AVAILABLE");
    }

    @Test
    void duplicateRideRequestsStartMatchingOnce() {
        UUID rideId = requestRide(RideType.ECONOMY, CENTER);
        var ride = new RidePayload(UUID.randomUUID(), null, RideType.ECONOMY, CENTER, AIRPORT,
                new BigDecimal("339.00"), 22111, 1359, null);

        assertThat(matching.onRideRequested(rideId, ride, Instant.now())).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM match_jobs WHERE ride_id = :id").param("id", rideId)
                .query(Integer.class).single()).isEqualTo(1);
    }

    private TestDriver npcDriver(RideType type, GeoPoint at) {
        TestDriver d = onlineDriver(type, at);
        jdbc.sql("UPDATE drivers SET simulated = true WHERE user_id = :id").param("id", d.id()).update();
        return d;
    }

    private UUID requestRideOfNpc(RideType type, GeoPoint pickup) {
        UUID rideId = UUID.randomUUID();
        matching.onRideRequested(rideId, new RidePayload(UUID.randomUUID(), null, type, pickup, AIRPORT,
                new BigDecimal("339.00"), 22111, 1359, null, null, true), Instant.now());
        return rideId;
    }

    @Test
    void aRealRidersRideGoesToARealDriverBeforeCloserNpcs() {
        TestDriver npc = npcDriver(RideType.ECONOMY, north(CENTER, 300));
        TestDriver person = onlineDriver(RideType.ECONOMY, north(CENTER, 6_000)); // outside the first two radii
        UUID rideId = requestRide(RideType.ECONOMY, CENTER);

        awaitOfferTo(person, rideId);
        assertNoOffer(npc);

        api().post("/api/drivers/me/offers/" + rideId + "/reject", person.token(), null);
        awaitOfferTo(npc, rideId);
    }

    @Test
    void anNpcsRideGoesToARealDriverFirstOnlyNearby() {
        TestDriver npc = npcDriver(RideType.COMFORT, north(CENTER, 200));
        TestDriver near = onlineDriver(RideType.COMFORT, north(CENTER, 2_000));
        UUID first = requestRideOfNpc(RideType.COMFORT, CENTER);
        awaitOfferTo(near, first);
        assertThat(api().post("/api/drivers/me/offers/" + first + "/accept", near.token(), null)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(outboxField(first, "DRIVER_ASSIGNED", "simulated")).isEqualTo("true");

        onlineDriver(RideType.COMFORT, north(CENTER, 4_000)); // a person, but beyond the first radius
        UUID second = requestRideOfNpc(RideType.COMFORT, CENTER);
        awaitOfferTo(npc, second);
    }

    @Test
    void busyDriversNearbyDoNotHideFreeDriversFurtherOut() {
        TestDriver busyNearby = onlineDriver(RideType.ECONOMY, north(CENTER, 500));
        TestDriver freeFurtherOut = onlineDriver(RideType.ECONOMY, north(CENTER, 4_000)); // outside 3 km, inside 5 km
        // busyNearby is holding another ride's offer
        redis.opsForValue().set(RedisKeys.driverLock(busyNearby.id()), UUID.randomUUID().toString(), Duration.ofSeconds(30));

        UUID rideId = requestRide(RideType.ECONOMY, CENTER);

        awaitOfferTo(freeFurtherOut, rideId);
        assertNoOffer(busyNearby);
    }

    @Test
    void driverStillSearchableInRedisButOnATripIsSkippedAndTheProjectionRepaired() {
        TestDriver stale = onlineDriver(RideType.ECONOMY, north(CENTER, 300));
        TestDriver next = onlineDriver(RideType.ECONOMY, north(CENTER, 900));
        // the driver started a trip, but the after-commit Redis update was lost
        jdbc.sql("UPDATE drivers SET status = 'ON_TRIP', current_ride_id = :r WHERE user_id = :id")
                .param("r", UUID.randomUUID()).param("id", stale.id()).update();

        UUID rideId = requestRide(RideType.ECONOMY, CENTER);

        awaitOfferTo(next, rideId);
        assertNoOffer(stale);
        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(redis.opsForHash().get(RedisKeys.driverState(stale.id()), "status")).isEqualTo("ON_TRIP");
            assertThat(redis.opsForZSet().score(RedisKeys.driversGeo(RideType.ECONOMY), stale.id().toString())).isNull();
        });
    }

    @Test
    void aJobLookedAtBeforeItsOfferExpiresIsDueAgainAtExpiry() {
        TestDriver d = onlineDriver(RideType.COMFORT, north(CENTER, 300));
        UUID rideId = requestRide(RideType.COMFORT, CENTER);
        awaitOfferTo(d, rideId);

        // e.g. a second instance whose clock runs ahead claimed the job early
        assertThatCode(() -> matching.process(rideId)).doesNotThrowAnyException();

        assertThat(jobStatus(rideId)).isEqualTo("OFFERED");
        assertThat(jdbc.sql("""
                SELECT j.due_at = o.expires_at FROM match_jobs j
                JOIN match_offers o ON o.ride_id = j.ride_id AND o.outcome = 'PENDING'
                WHERE j.ride_id = :id""").param("id", rideId).query(Boolean.class).single()).isTrue();
    }

    @Test
    void anOfferThatFailsToSaveReleasesTheDriverLock() {
        TestDriver d = onlineDriver(RideType.XL, north(CENTER, 300));
        jdbc.sql("ALTER TABLE match_offers ADD CONSTRAINT ck_test_refuse_driver CHECK (driver_id <> '" + d.id() + "')").update();
        try {
            UUID rideId = requestRide(RideType.XL, CENTER);
            // let the scheduler claim the job and make (and fail) its own attempt first
            await().atMost(WAIT).until(() -> jdbc.sql("SELECT due_at > now() + interval '5 seconds' FROM match_jobs WHERE ride_id = :id")
                    .param("id", rideId).query(Boolean.class).single());
            await().pollDelay(Duration.ofMillis(500)).atMost(WAIT).until(() -> locks.holder(d.id()) == null);

            assertThatThrownBy(() -> matching.process(rideId)).isInstanceOf(RuntimeException.class);

            assertThat(locks.holder(d.id())).isNull();
            assertNoOffer(d);
        } finally {
            jdbc.sql("ALTER TABLE match_offers DROP CONSTRAINT ck_test_refuse_driver").update();
        }
    }

    @Test
    void searchDeadlineCountsFromWhenTheRiderBooked() {
        Instant now = Instant.now();
        UUID fresh = requestRide(RideType.ECONOMY, CENTER, now);
        UUID waitedAnHour = requestRide(RideType.ECONOMY, CENTER, now.minus(Duration.ofHours(1)));

        // search-timeout is 7 s and offer-timeout (the minimum) 2 s in tests
        assertThat(deadline(fresh)).isBetween(now.plusSeconds(6), now.plusSeconds(8));
        assertThat(deadline(waitedAnHour)).isBetween(now.plusSeconds(1), now.plusSeconds(4));
        awaitJobStatus(waitedAnHour, "NO_DRIVER_FOUND");
    }

    private Instant deadline(UUID rideId) {
        return jdbc.sql("SELECT search_deadline FROM match_jobs WHERE ride_id = :id").param("id", rideId)
                .query(Timestamp.class).single().toInstant();
    }

    @Test
    void offersAndTheirEndArePushedToTheDriversWebSocketChannel() throws Exception {
        List<JsonNode> pushes = new CopyOnWriteArrayList<>();
        var listener = new RedisMessageListenerContainer();
        listener.setConnectionFactory(redis.getConnectionFactory());
        listener.addMessageListener((message, pattern) -> pushes.add(json.readTree(message.getBody())),
                new ChannelTopic(WsPush.CHANNEL));
        listener.afterPropertiesSet();
        listener.start();
        try {
            TestDriver first = onlineDriver(RideType.ECONOMY, north(CENTER, 300));
            TestDriver second = onlineDriver(RideType.ECONOMY, north(CENTER, 900));
            Thread.sleep(300); // let the subscription settle
            UUID rideId = requestRide(RideType.ECONOMY, CENTER);
            awaitOfferTo(first, rideId);
            api().post("/api/drivers/me/offers/" + rideId + "/reject", first.token(), null);
            awaitOfferTo(second, rideId);
            api().post("/api/drivers/me/offers/" + rideId + "/accept", second.token(), null);

            await().atMost(WAIT).untilAsserted(() -> assertThat(pushes).hasSize(4));
            assertThat(pushes).allSatisfy(p -> {
                assertThat(p.path("destination").asString()).isEqualTo("/queue/offers");
                assertThat(p.path("payload").path("rideId").asString()).isEqualTo(rideId.toString());
            });
            assertThat(pushes).extracting(p -> p.path("user").asString() + " " + p.path("payload").path("type").asString()
                            + " " + (p.path("payload").path("reason").isString() ? p.path("payload").path("reason").asString() : ""))
                    .containsExactly(
                            first.id() + " OFFER ",
                            first.id() + " OFFER_CLOSED REJECTED",
                            second.id() + " OFFER ",
                            second.id() + " OFFER_CLOSED ACCEPTED");
            JsonNode offer = pushes.get(0).path("payload").path("offer");
            assertThat(offer.path("rideType").asString()).isEqualTo("ECONOMY");
            assertThat(offer.path("expiresAt").asString()).isNotBlank();
            assertThat(offer.path("pickup").path("lat").asDouble()).isEqualTo(CENTER.lat());
        } finally {
            listener.stop();
            listener.destroy();
        }
    }
}
