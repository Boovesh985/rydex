package com.rydex.ride.admin;

import static com.rydex.ride.TestApi.token;
import static com.rydex.ride.TestApi.userId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.rydex.ride.IntegrationTest;
import com.rydex.ride.geo.RouteResult;
import com.rydex.ride.ride.RideService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

class AdminStatsTests extends IntegrationTest {

    /** Far from every other test's pickups, so this test's heatmap cell is its own. */
    static final Map<String, Object> PICKUP = Map.of("lat", 13.2001, "lng", 80.3001, "address", "Ennore");
    static final Map<String, Object> DROP = Map.of("lat", 13.0500, "lng", 80.2824, "address", "Marina Beach");

    @Autowired
    RideService rideService;

    @BeforeEach
    void stubRouting() {
        given(osrm.route(any(), any())).willReturn(List.of(new RouteResult.Alternative(5_000, 900, "poly")));
    }

    private String admin() {
        return token(api().post("/api/auth/login", null, Map.of("email", "admin@rydex.dev", "password", "Rydex@123")).getBody());
    }

    private UUID book(String rider) {
        var res = api().call(HttpMethod.POST, "/api/rides", rider,
                Map.of("pickup", PICKUP, "drop", DROP, "rideType", "ECONOMY"),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        return UUID.fromString(res.getBody().path("id").asString());
    }

    private JsonNode stats(String admin) {
        var res = api().get("/api/admin/stats?hours=1", admin);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return res.getBody();
    }

    @Test
    void statsCountRidesOutcomesAndRevenue() {
        String admin = admin();
        JsonNode before = stats(admin);

        // one completed ride, one cancelled, one still searching
        String rider1 = token(api().register("RIDER"));
        var driverAuth = api().register("DRIVER");
        String driver = token(driverAuth);
        UUID done = book(rider1);
        rideService.assignDriver(done, userId(driverAuth), "SYSTEM:test");
        api().post("/api/rides/" + done + "/arrived", driver, null);
        String otp = api().get("/api/rides/" + done, rider1).getBody().path("otp").asString();
        api().post("/api/rides/" + done + "/start", driver, Map.of("otp", otp));
        assertThat(api().post("/api/rides/" + done + "/complete", driver, null).getStatusCode().value()).isEqualTo(200);

        String rider2 = token(api().register("RIDER"));
        api().post("/api/rides/" + book(rider2) + "/cancel", rider2, Map.of("reason", "test"));

        book(token(api().register("RIDER")));

        JsonNode after = stats(admin);
        assertThat(after.path("hours").asInt()).isEqualTo(1);
        assertThat(after.path("requested").asLong() - before.path("requested").asLong()).isEqualTo(3);
        assertThat(after.path("completed").asLong() - before.path("completed").asLong()).isEqualTo(1);
        assertThat(after.path("cancelled").asLong() - before.path("cancelled").asLong()).isEqualTo(1);
        assertThat(after.path("inFlight").path("REQUESTED").asLong()
                - before.path("inFlight").path("REQUESTED").asLong()).isEqualTo(1);
        assertThat(after.path("revenue").decimalValue().subtract(before.path("revenue").decimalValue()))
                .isPositive();
        assertThat(after.path("avgFare").decimalValue()).isPositive();
        assertThat(after.path("matchRate").asDouble()).isBetween(0.0, 1.0);
        assertThat(after.path("avgAssignS").isNumber()).isTrue();
        assertThat(after.path("riders").asLong()).isGreaterThanOrEqualTo(8); // 5 seeded + 3 here
        assertThat(after.path("drivers").asLong()).isGreaterThanOrEqualTo(51);
    }

    @Test
    void demandCountsRequestsPerCell() {
        book(token(api().register("RIDER")));
        book(token(api().register("RIDER")));

        var res = api().get("/api/admin/demand?hours=1", admin());

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        JsonNode cell = null;
        for (JsonNode c : res.getBody()) {
            if (Math.abs(c.path("lat").asDouble() - 13.2) < 1e-6 && Math.abs(c.path("lng").asDouble() - 80.3) < 1e-6) {
                cell = c;
            }
        }
        assertThat(cell).as("cell around the pickup").isNotNull();
        assertThat(cell.path("requests").asLong()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void onlyAdminsMaySeeStats() {
        String rider = token(api().register("RIDER"));

        assertThat(api().get("/api/admin/stats", rider).getStatusCode().value()).isEqualTo(403);
        assertThat(api().get("/api/admin/demand", rider).getStatusCode().value()).isEqualTo(403);
        assertThat(api().get("/api/admin/stats", null).getStatusCode().value()).isEqualTo(401);
        assertThat(api().get("/api/admin/stats?hours=0", admin()).getStatusCode().value()).isEqualTo(400);
    }
}
