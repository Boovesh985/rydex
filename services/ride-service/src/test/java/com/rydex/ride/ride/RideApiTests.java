package com.rydex.ride.ride;

import static com.rydex.ride.TestApi.token;
import static com.rydex.ride.TestApi.userId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.rydex.common.RedisKeys;
import com.rydex.common.RideType;
import com.rydex.common.geo.Geohash;
import com.rydex.ride.IntegrationTest;
import com.rydex.ride.geo.OsrmClient;
import com.rydex.ride.geo.RouteResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Point;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

class RideApiTests extends IntegrationTest {

    // Chennai Central -> Marina Beach (lat/lng)
    static final Map<String, Object> PICKUP = Map.of("lat", 13.0827, "lng", 80.2707, "address", "Chennai Central");
    static final Map<String, Object> DROP = Map.of("lat", 13.0500, "lng", 80.2824, "address", "Marina Beach");

    @Autowired
    RideService rideService;

    @Autowired
    OtpService otpService;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void stubRouting() {
        given(osrm.route(any(), any())).willReturn(List.of(
                new RouteResult.Alternative(5_000, 900, "encoded_polyline"),
                new RouteResult.Alternative(5_600, 960, "alt_polyline")));
        redis.delete(RedisKeys.driversGeo(RideType.ECONOMY));
    }

    @Test
    void estimateQuotesEveryRideType() {
        String rider = token(api().register("RIDER"));

        var res = api().post("/api/rides/estimate", rider, Map.of("pickup", PICKUP, "drop", DROP));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        JsonNode body = res.getBody();
        assertThat(body.path("distanceM").asInt()).isEqualTo(5_000);
        assertThat(body.path("route").path("source").asString()).isEqualTo("OSRM");
        assertThat(body.path("options")).hasSize(3);
        // ECONOMY: 40 + 12*5 + 1.5*15 = 122.5 -> 123
        JsonNode economy = body.path("options").get(0);
        assertThat(economy.path("rideType").asString()).isEqualTo("ECONOMY");
        assertThat(economy.path("fare").decimalValue()).isEqualByComparingTo("123");
        assertThat(economy.path("etaMinutes").isNull()).isTrue();
    }

    @Test
    void estimateShowsEtaWhenADriverIsNearby() {
        String rider = token(api().register("RIDER"));
        // ~1.1 km north of the pickup
        redis.opsForGeo().add(RedisKeys.driversGeo(RideType.ECONOMY), new Point(80.2707, 13.0927),
                UUID.randomUUID().toString());

        var res = api().post("/api/rides/estimate", rider, Map.of("pickup", PICKUP, "drop", DROP));

        JsonNode economy = res.getBody().path("options").get(0);
        assertThat(economy.path("driversNearby").asInt()).isEqualTo(1);
        assertThat(economy.path("etaMinutes").asInt()).isBetween(3, 6);
    }

    @Test
    void estimateFallsBackToStraightLineWhenOsrmIsDown() {
        given(osrm.route(any(), any())).willThrow(new OsrmClient.RoutingException("down"));
        String rider = token(api().register("RIDER"));
        var farPickup = Map.<String, Object>of("lat", 12.9941, "lng", 80.1709);

        var res = api().post("/api/rides/estimate", rider, Map.of("pickup", farPickup, "drop", DROP));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().path("route").path("source").asString()).isEqualTo("ESTIMATED");
        assertThat(res.getBody().path("distanceM").asInt()).isGreaterThan(10_000);
    }

    @Test
    void estimateRejectsTripsThatAreTooShortOrInvalid() {
        String rider = token(api().register("RIDER"));

        var tooShort = api().post("/api/rides/estimate", rider, Map.of("pickup", PICKUP, "drop", PICKUP));
        var invalid = api().post("/api/rides/estimate", rider,
                Map.of("pickup", Map.of("lat", 200, "lng", 80), "drop", DROP));

        assertThat(tooShort.getStatusCode().value()).isEqualTo(422);
        assertThat(invalid.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void driversCannotEstimateOrBook() {
        String driver = token(api().register("DRIVER"));

        assertThat(api().post("/api/rides/estimate", driver, Map.of("pickup", PICKUP, "drop", DROP))
                .getStatusCode().value()).isEqualTo(403);
        assertThat(api().post("/api/rides", driver, booking()).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void bookingCreatesRideHistoryOutboxEventAndDemand() {
        var auth = api().register("RIDER");
        long bucket = RedisKeys.demandBucket(Instant.now());
        String demandKey = RedisKeys.demand(Geohash.encode(13.0827, 80.2707, 6), bucket);
        String before = redis.opsForValue().get(demandKey);

        var res = api().post("/api/rides", token(auth), booking());

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        JsonNode ride = res.getBody();
        UUID rideId = UUID.fromString(ride.path("id").asString());
        assertThat(res.getHeaders().getLocation()).hasToString("/api/rides/" + rideId);
        assertThat(ride.path("status").asString()).isEqualTo("REQUESTED");
        assertThat(ride.path("riderId").asString()).isEqualTo(userId(auth).toString());
        assertThat(ride.path("estFare").decimalValue()).isEqualByComparingTo("123");
        assertThat(ride.path("otp").asString()).matches("\\d{4}");
        assertThat(ride.path("pickup").path("address").asString()).isEqualTo("Chennai Central");

        var events = outboxEvents(rideId);
        assertThat(events).containsExactly("RIDE_REQUESTED");
        var payload = jdbc.sql("""
                        select payload->>'eventType' as type, payload->'payload'->>'rideType' as ride_type,
                               topic, msg_key from outbox where aggregate_id = ?""")
                .param(rideId.toString()).query().singleRow();
        assertThat(payload).containsEntry("type", "RIDE_REQUESTED").containsEntry("ride_type", "ECONOMY")
                .containsEntry("topic", "ride.events").containsEntry("msg_key", rideId.toString());

        long demand = Long.parseLong(redis.opsForValue().get(demandKey));
        assertThat(demand).isEqualTo(before == null ? 1 : Long.parseLong(before) + 1);
    }

    @Test
    void anNpcRidersRideIsMarkedSimulatedInItsViewAndEvents() {
        var auth = api().register("RIDER");
        String rider = token(auth);
        String human = token(api().register("RIDER"));
        jdbc.sql("update users set simulated = true where id = ?").param(userId(auth)).update();

        UUID npcRide = book(rider);
        UUID humanRide = book(human);

        assertThat(api().get("/api/rides/" + npcRide, rider).getBody().path("simulated").asBoolean()).isTrue();
        assertThat(api().get("/api/rides/" + humanRide, human).getBody().path("simulated").asBoolean()).isFalse();
        String flag = jdbc.sql("select payload->'payload'->>'simulated' from outbox where aggregate_id = ?")
                .param(npcRide.toString()).query(String.class).single();
        assertThat(flag).isEqualTo("true");
    }

    @Test
    void anNpcRiderReadsThePinOutToTheDriverAtThePickup() {
        var npcAuth = api().register("RIDER");
        String npc = token(npcAuth);
        String human = token(api().register("RIDER"));
        jdbc.sql("update users set simulated = true where id = ?").param(userId(npcAuth)).update();
        var driverAuth = api().register("DRIVER");
        String driver = token(driverAuth);
        UUID npcRide = book(npc);
        rideService.assignDriver(npcRide, userId(driverAuth), "SYSTEM:test");

        assertThat(api().get("/api/rides/" + npcRide, driver).getBody().path("otp").isNull())
                .as("not before the car is there").isTrue();
        driverAtPickup(npcRide);
        var arrived = api().post("/api/rides/" + npcRide + "/arrived", driver, null).getBody();
        assertThat(arrived.path("otp").asString()).isEqualTo(otpService.otpFor(npcRide));
        assertThat(api().get("/api/rides/" + npcRide, driver).getBody().path("otp").asString())
                .isEqualTo(otpService.otpFor(npcRide));

        // a real rider tells the driver themselves
        var otherDriver = api().register("DRIVER");
        UUID humanRide = book(human);
        rideService.assignDriver(humanRide, userId(otherDriver), "SYSTEM:test");
        driverAtPickup(humanRide);
        assertThat(api().post("/api/rides/" + humanRide + "/arrived", token(otherDriver), null).getBody()
                .path("otp").isNull()).isTrue();

        api().post("/api/rides/" + npcRide + "/start", driver, Map.of("otp", otpService.otpFor(npcRide)));
        assertThat(api().get("/api/rides/" + npcRide, driver).getBody().path("otp").isNull())
                .as("gone once the trip starts").isTrue();
    }

    @Test
    void idempotencyKeyReplaysTheOriginalRide() {
        String rider = token(api().register("RIDER"));
        var headers = Map.of("Idempotency-Key", "book-" + UUID.randomUUID());

        var first = api().call(HttpMethod.POST, "/api/rides", rider, booking(), headers);
        var replay = api().call(HttpMethod.POST, "/api/rides", rider, booking(), headers);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(replay.getStatusCode().value()).isEqualTo(201);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getBody().path("id").asString()).isEqualTo(first.getBody().path("id").asString());
    }

    @Test
    void riderCanHaveOnlyOneActiveRide() {
        String rider = token(api().register("RIDER"));

        assertThat(api().post("/api/rides", rider, booking()).getStatusCode().value()).isEqualTo(201);
        var second = api().post("/api/rides", rider, booking());

        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(second.getBody().path("detail").asString()).contains("already have a ride");
    }

    @Test
    void fullLifecycleFromBookingToCompletion() {
        String rider = token(api().register("RIDER"));
        var driverAuth = api().register("DRIVER");
        String driver = token(driverAuth);
        UUID rideId = book(rider);

        rideService.assignDriver(rideId, userId(driverAuth), "SYSTEM:test");
        assertThat(api().get("/api/rides/" + rideId, driver).getBody().path("otp").isNull())
                .as("driver must not see the OTP").isTrue();
        String otp = api().get("/api/rides/" + rideId, rider).getBody().path("otp").asString();
        assertThat(otp).isEqualTo(otpService.otpFor(rideId));

        // OTP cannot be used before the driver has arrived
        assertThat(api().post("/api/rides/" + rideId + "/start", driver, Map.of("otp", otp))
                .getStatusCode().value()).isEqualTo(409);
        // not before the car is at the pickup, and not without knowing where the car is
        var unknown = api().post("/api/rides/" + rideId + "/arrived", driver, null);
        assertThat(unknown.getStatusCode().value()).isEqualTo(409);
        assertThat(unknown.getBody().path("detail").asString()).contains("share your location");
        driverAt(userId(driverAuth), 13.0500, 80.2500);
        var tooFar = api().post("/api/rides/" + rideId + "/arrived", driver, null);
        assertThat(tooFar.getStatusCode().value()).isEqualTo(409);
        assertThat(tooFar.getBody().path("detail").asString()).contains("from the pickup");
        driverAtPickup(rideId);
        assertThat(api().post("/api/rides/" + rideId + "/arrived", driver, null).getStatusCode().value())
                .isEqualTo(200);
        String wrongOtp = otp.equals("0000") ? "1111" : "0000";
        var wrong = api().post("/api/rides/" + rideId + "/start", driver, Map.of("otp", wrongOtp));
        assertThat(wrong.getStatusCode().value()).isEqualTo(422);
        assertThat(wrong.getBody().path("detail").asString()).contains("Wrong PIN").contains("4 tries left");
        assertThat(api().post("/api/rides/" + rideId + "/start", driver, Map.of("otp", otp))
                .getBody().path("status").asString()).isEqualTo("IN_PROGRESS");
        var completed = api().post("/api/rides/" + rideId + "/complete", driver, null);

        assertThat(completed.getStatusCode().value()).isEqualTo(200);
        assertThat(completed.getBody().path("finalFare").decimalValue()).isEqualByComparingTo("123");
        JsonNode view = api().get("/api/rides/" + rideId, rider).getBody();
        assertThat(view.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(view.path("otp").isNull()).isTrue();
        assertThat(view.path("timeline")).hasSize(5);
        assertThat(outboxEvents(rideId))
                .containsExactly("RIDE_REQUESTED", "DRIVER_ARRIVED", "RIDE_STARTED", "RIDE_COMPLETED");
        // cannot complete twice
        assertThat(api().post("/api/rides/" + rideId + "/complete", driver, null).getStatusCode().value())
                .isEqualTo(409);
    }

    @Test
    void onlyTheAssignedDriverCanDriveTheRide() {
        String rider = token(api().register("RIDER"));
        var assigned = api().register("DRIVER");
        String otherDriver = token(api().register("DRIVER"));
        UUID rideId = book(rider);
        rideService.assignDriver(rideId, userId(assigned), "SYSTEM:test");

        assertThat(api().post("/api/rides/" + rideId + "/arrived", otherDriver, null).getStatusCode().value())
                .isEqualTo(403);
        assertThat(api().post("/api/rides/" + rideId + "/arrived", rider, null).getStatusCode().value())
                .isEqualTo(403);
    }

    @Test
    void otpAttemptsAreLimited() {
        String rider = token(api().register("RIDER"));
        var driverAuth = api().register("DRIVER");
        String driver = token(driverAuth);
        UUID rideId = book(rider);
        rideService.assignDriver(rideId, userId(driverAuth), "SYSTEM:test");
        driverAtPickup(rideId);
        api().post("/api/rides/" + rideId + "/arrived", driver, null);
        String wrong = otpService.otpFor(rideId).equals("0000") ? "1111" : "0000";

        for (int i = 0; i < RideService.MAX_OTP_ATTEMPTS; i++) {
            assertThat(api().post("/api/rides/" + rideId + "/start", driver, Map.of("otp", wrong))
                    .getStatusCode().value()).isEqualTo(422);
        }
        var locked = api().post("/api/rides/" + rideId + "/start", driver,
                Map.of("otp", otpService.otpFor(rideId)));

        assertThat(locked.getStatusCode().value()).isEqualTo(429);
        assertThat(locked.getHeaders().getFirst("Retry-After")).isNotNull();
    }

    @Test
    void riderCancelsThenCanBookAgain() {
        String rider = token(api().register("RIDER"));
        UUID rideId = book(rider);

        var cancelled = api().post("/api/rides/" + rideId + "/cancel", rider, Map.of("reason", "Changed plans"));
        var again = api().post("/api/rides/" + rideId + "/cancel", rider, null);

        assertThat(cancelled.getBody().path("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.getBody().path("cancelledBy").asString()).isEqualTo("RIDER");
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody().path("from").asString()).isEqualTo("CANCELLED");
        assertThat(outboxEvents(rideId)).containsExactly("RIDE_REQUESTED", "RIDE_CANCELLED");
        assertThat(api().post("/api/rides", rider, booking()).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void noDriverFoundEndsTheRide() {
        String rider = token(api().register("RIDER"));
        UUID rideId = book(rider);

        rideService.markNoDriverFound(rideId, "SYSTEM:test");

        assertThat(api().get("/api/rides/" + rideId, rider).getBody().path("status").asString())
                .isEqualTo("NO_DRIVER_FOUND");
    }

    @Test
    void ridesArePrivateToTheirParticipants() {
        String owner = token(api().register("RIDER"));
        String stranger = token(api().register("RIDER"));
        UUID rideId = book(owner);

        assertThat(api().get("/api/rides/" + rideId, stranger).getStatusCode().value()).isEqualTo(404);
        assertThat(api().get("/api/rides/" + UUID.randomUUID(), owner).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void historyListsOwnRidesNewestFirst() {
        String rider = token(api().register("RIDER"));
        UUID first = book(rider);
        api().post("/api/rides/" + first + "/cancel", rider, null);
        UUID second = book(rider);

        var page = api().get("/api/rides?mine=true&page=0&size=10", rider).getBody();

        assertThat(page.path("total").asInt()).isEqualTo(2);
        assertThat(page.path("items").get(0).path("id").asString()).isEqualTo(second.toString());
        assertThat(page.path("items").get(1).path("id").asString()).isEqualTo(first.toString());
    }

    @Test
    void adminsCanListTheRidesInFlight() {
        String rider = token(api().register("RIDER"));
        UUID cancelled = book(rider);
        api().post("/api/rides/" + cancelled + "/cancel", rider, null);
        UUID inFlight = book(rider);
        String admin = token(api().post("/api/auth/login", null,
                Map.of("email", "admin@rydex.dev", "password", "Rydex@123")).getBody());

        var active = api().get("/api/rides?mine=false&active=true&size=100", admin).getBody();
        var all = api().get("/api/rides?mine=false&size=100", admin).getBody();
        var riderView = api().get("/api/rides?active=true", rider).getBody();

        assertThat(active.path("items").valueStream().map(r -> r.path("id").asString()))
                .contains(inFlight.toString()).doesNotContain(cancelled.toString());
        assertThat(active.path("items").valueStream().map(r -> r.path("status").asString()))
                .allMatch(s -> List.of("REQUESTED", "DRIVER_ASSIGNED", "DRIVER_ARRIVED", "IN_PROGRESS").contains(s));
        assertThat(all.path("items").valueStream().map(r -> r.path("id").asString())).contains(cancelled.toString());
        // riders keep seeing their own history whatever they ask for
        assertThat(riderView.path("total").asInt()).isEqualTo(2);
    }

    private UUID book(String riderToken) {
        var res = api().post("/api/rides", riderToken, booking());
        assertThat(res.getStatusCode().value()).as(String.valueOf(res.getBody())).isEqualTo(201);
        return UUID.fromString(res.getBody().path("id").asString());
    }

    private static Map<String, Object> booking() {
        return Map.of("pickup", PICKUP, "drop", DROP, "rideType", "ECONOMY");
    }

    private List<String> outboxEvents(UUID rideId) {
        return jdbc.sql("select event_type from outbox where aggregate_id = ? order by created_at, id")
                .param(rideId.toString()).query(String.class).list();
    }
}
