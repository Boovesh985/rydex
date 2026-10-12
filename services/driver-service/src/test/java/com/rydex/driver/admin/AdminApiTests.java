package com.rydex.driver.admin;

import static org.assertj.core.api.Assertions.assertThat;

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
}
