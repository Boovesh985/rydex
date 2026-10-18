package com.rydex.driver.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import com.rydex.common.RedisKeys;
import com.rydex.common.RideType;
import com.rydex.common.geo.Geohash;
import com.rydex.driver.IntegrationTest;
import com.rydex.driver.TestTokens;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AdminApiTests extends IntegrationTest {

    @Test
    void adminEndpointsRequireTheAdminRole() {
        String driver = TestTokens.driver(UUID.randomUUID());

        assertThat(api().get("/api/admin/grid?lat=13.08&lng=80.27", driver).getStatusCode().value()).isEqualTo(403);
        assertThat(api().get("/api/admin/drivers/live", TestTokens.rider(UUID.randomUUID())).getStatusCode().value()).isEqualTo(403);
        assertThat(api().get("/api/admin/grid?lat=13.08&lng=80.27", null).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void gridShowsTheThreeByThreeCellsWithDriversAndDemand() {
        String center = Geohash.encode(CENTER, Geohash.MATCHING_PRECISION);
        String north = Geohash.neighbours(center).get(0);
        onlineDriver(RideType.ECONOMY, CENTER);
        onlineDriver(RideType.XL, CENTER);
        onlineDriver(RideType.COMFORT, Geohash.center(north));
        onlineDriver(RideType.ECONOMY, north(CENTER, 20_000)); // outside the grid
        redis.opsForValue().set(RedisKeys.demand(center, Instant.now()), "4"); // written by ride-service on booking

        JsonNode grid = api().get("/api/admin/grid?lat=" + CENTER.lat() + "&lng=" + CENTER.lng(), TestTokens.admin()).getBody();

        assertThat(grid.path("center").asString()).isEqualTo(center);
        assertThat(grid.path("precision").asInt()).isEqualTo(6);
        assertThat(grid.path("cells")).hasSize(9);
        assertThat(grid.path("availableDrivers").asLong()).isEqualTo(3);
        assertThat(grid.path("recentRequests").asLong()).isEqualTo(4);
        JsonNode c = grid.path("cells").get(0);
        assertThat(c.path("position").asString()).isEqualTo("C");
        assertThat(c.path("availableDrivers").asLong()).isEqualTo(2);
        assertThat(c.path("minLat").asDouble()).isLessThan(CENTER.lat());
        assertThat(c.path("maxLat").asDouble()).isGreaterThan(CENTER.lat());
        JsonNode n = grid.path("cells").get(1);
        assertThat(n.path("position").asString()).isEqualTo("N");
        assertThat(n.path("geohash").asString()).isEqualTo(north);
        assertThat(n.path("availableDrivers").asLong()).isEqualTo(1);
    }

    @Test
    void gridValidatesCoordinates() {
        assertThat(api().get("/api/admin/grid?lat=91&lng=80", TestTokens.admin()).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void liveDriversListsRecentHeartbeatsWithStatus() {
        TestDriver online = onlineDriver(RideType.COMFORT, CENTER);

        JsonNode live = api().get("/api/admin/drivers/live", TestTokens.admin()).getBody();

        assertThat(live).hasSize(1);
        assertThat(live.get(0).path("driverId").asString()).isEqualTo(online.id().toString());
        assertThat(live.get(0).path("status").asString()).isEqualTo("AVAILABLE");
        assertThat(live.get(0).path("rideType").asString()).isEqualTo("COMFORT");
        assertThat(live.get(0).path("geohash").asString()).hasSize(6);
    }

    @Test
    void adminLooksUpOneDriver() {
        TestDriver online = onlineDriver(RideType.BIKE, CENTER);

        JsonNode d = api().get("/api/admin/drivers/" + online.id(), TestTokens.admin()).getBody();

        assertThat(d.path("id").asString()).isEqualTo(online.id().toString());
        assertThat(d.path("vehicleType").asString()).isEqualTo("BIKE");
        assertThat(d.path("status").asString()).isEqualTo("AVAILABLE");
        assertThat(d.path("lastLocation").path("lat").asDouble()).isEqualTo(CENTER.lat(), within(1e-4));
        assertThat(api().get("/api/admin/drivers/" + online.id(), online.token()).getStatusCode().value()).isEqualTo(403);
        assertThat(api().get("/api/admin/drivers/" + UUID.randomUUID(), TestTokens.admin()).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void adminFindsDriversByNameOrPlate() {
        TestDriver bike = onlineDriver(RideType.BIKE, CENTER);
        String plate = jdbc.sql("SELECT vehicle_number FROM drivers WHERE user_id = :id").param("id", bike.id())
                .query(String.class).single(); // e.g. TST-1a2b3c4d
        String name = jdbc.sql("SELECT name FROM drivers WHERE user_id = :id").param("id", bike.id()).query(String.class).single();

        // part of the plate, any case, spaces ignored
        JsonNode byPlate = api().get("/api/admin/drivers?q=" + plate.substring(4, 10).toLowerCase(), TestTokens.admin()).getBody();
        assertThat(byPlate.valueStream().map(d -> d.path("id").asString())).contains(bike.id().toString());
        assertThat(byPlate.get(0).path("lastLocation").path("lat").asDouble()).isEqualTo(CENTER.lat(), within(1e-4));
        JsonNode byName = api().get("/api/admin/drivers?q=" + name.substring(0, Math.min(name.length(), 6)), TestTokens.admin()).getBody();
        assertThat(byName.size()).isBetween(1, 10);

        assertThat(api().get("/api/admin/drivers?q=x", TestTokens.admin()).getStatusCode().value()).isEqualTo(400);
        assertThat(api().get("/api/admin/drivers?q=" + plate, bike.token()).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void candidatesDryRunRanksWithoutOffering() {
        TestDriver near = onlineDriver(RideType.ECONOMY, north(CENTER, 400));
        TestDriver far = onlineDriver(RideType.ECONOMY, north(CENTER, 2400));

        JsonNode ranked = api().get("/api/admin/matching/candidates?lat=" + CENTER.lat() + "&lng=" + CENTER.lng()
                + "&rideType=ECONOMY", TestTokens.admin()).getBody();

        assertThat(ranked).hasSize(2);
        assertThat(ranked.get(0).path("driverId").asString()).isEqualTo(near.id().toString());
        assertThat(ranked.get(1).path("driverId").asString()).isEqualTo(far.id().toString());
        assertThat(currentOffer(near).getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void unknownMatchingJobIs404() {
        assertThat(api().get("/api/admin/matching/" + UUID.randomUUID(), TestTokens.admin()).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void adminCanReleaseADriverStuckOnATrip() {
        TestDriver d = onlineDriver(RideType.ECONOMY, CENTER);
        UUID rideId = requestRide(RideType.ECONOMY, CENTER);
        await().atMost(WAIT).until(() -> currentOffer(d).getStatusCode().value() == 200);
        api().post("/api/drivers/me/offers/" + rideId + "/accept", d.token(), null);
        String path = "/api/admin/drivers/" + d.id() + "/release";
        assertThat(api().post(path, d.token(), null).getStatusCode().value()).isEqualTo(403);

        // the ride's RIDE_COMPLETED never arrived (e.g. dead-lettered)
        var released = api().post(path, TestTokens.admin(), null);

        assertThat(released.getStatusCode().value()).isEqualTo(200);
        assertThat(released.getBody().path("status").asString()).isEqualTo("OFFLINE");
        assertThat(released.getBody().path("currentRideId").isNull()).isTrue();
        assertThat(redis.opsForHash().get(RedisKeys.driverState(d.id()), "status")).isEqualTo("OFFLINE");
        // the driver can go online again by themselves
        goOnline(d, CENTER);
        assertThat(api().post(path, TestTokens.admin(), null).getStatusCode().value()).isEqualTo(409);
        assertThat(api().post("/api/admin/drivers/" + UUID.randomUUID() + "/release", TestTokens.admin(), null)
                .getStatusCode().value()).isEqualTo(404);
    }
}
