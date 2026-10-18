package com.rydex.ride.ride;

import static com.rydex.ride.TestApi.token;
import static com.rydex.ride.TestApi.userId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.rydex.events.Topics;
import com.rydex.ride.IntegrationTest;
import com.rydex.ride.KafkaProbe;
import com.rydex.ride.geo.RouteResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The rider rates a completed trip, once; driver-service hears of it. */
class TripRatingTests extends IntegrationTest {

    static final Map<String, Object> PICKUP = Map.of("lat", 13.0827, "lng", 80.2707, "address", "Chennai Central");
    static final Map<String, Object> DROP = Map.of("lat", 13.0500, "lng", 80.2824, "address", "Marina Beach");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @BeforeEach
    void stubRouting() {
        given(osrm.route(any(), any())).willReturn(List.of(new RouteResult.Alternative(5_000, 900, "poly")));
    }

    private record Trip(UUID rideId, String rider, String driver) {
    }

    private Trip trip(String status) {
        String rider = token(api().register("RIDER"));
        JsonNode driverAuth = api().register("DRIVER");
        var res = api().post("/api/rides", rider, Map.of("pickup", PICKUP, "drop", DROP, "rideType", "ECONOMY"));
        UUID rideId = UUID.fromString(res.getBody().path("id").asString());
        jdbc.sql("""
                        UPDATE rides SET status = :status, driver_id = :driver, assigned_at = now(), arrived_at = now(),
                               started_at = now(), completed_at = CASE WHEN :status = 'COMPLETED' THEN now() END,
                               final_fare = CASE WHEN :status = 'COMPLETED' THEN est_fare END
                        WHERE id = :id""")
                .param("status", status).param("driver", userId(driverAuth)).param("id", rideId).update();
        return new Trip(rideId, rider, token(driverAuth));
    }

    @Test
    void theRiderRatesACompletedTripOnceAndTheDriverHearsOfIt() throws Exception {
        Trip t = trip("COMPLETED");

        var rated = api().post("/api/rides/" + t.rideId() + "/rating", t.rider(), Map.of("stars", 4, "comment", "  Smooth ride  "));

        assertThat(rated.getStatusCode().value()).isEqualTo(200);
        assertThat(rated.getBody().path("riderRating").asInt()).isEqualTo(4);
        assertThat(rated.getBody().path("riderComment").asString()).isEqualTo("Smooth ride");
        // once only
        assertThat(api().post("/api/rides/" + t.rideId() + "/rating", t.rider(), Map.of("stars", 5)).getStatusCode().value())
                .isEqualTo(409);
        // the rider and admins see it; the driver is not shown how each rider rated them
        assertThat(api().get("/api/rides/" + t.rideId(), t.rider()).getBody().path("riderRating").asInt()).isEqualTo(4);
        JsonNode driverView = api().get("/api/rides/" + t.rideId(), t.driver()).getBody();
        assertThat(driverView.path("riderRating").isNull()).isTrue();
        assertThat(driverView.path("riderComment").isNull()).isTrue();
        // RIDE_RATED carries the stars to driver-service
        var events = kafka().await(Topics.RIDE_EVENTS, r -> t.rideId().toString().equals(r.key())
                && "RIDE_RATED".equals(KafkaProbe.header(r, "eventType")), 1, Duration.ofSeconds(30));
        JsonNode payload = json.readTree(events.getFirst().value()).path("payload");
        assertThat(payload.path("rating").asInt()).isEqualTo(4);
        assertThat(payload.path("driverId").asString()).isNotBlank();
    }

    @Test
    void onlyItsRiderRatesAndOnlyOnceTheTripIsDone() {
        Trip underWay = trip("IN_PROGRESS");
        Trip done = trip("COMPLETED");

        assertThat(api().post("/api/rides/" + underWay.rideId() + "/rating", underWay.rider(), Map.of("stars", 5))
                .getStatusCode().value()).isEqualTo(409);
        assertThat(api().post("/api/rides/" + done.rideId() + "/rating", underWay.rider(), Map.of("stars", 5))
                .getStatusCode().value()).isEqualTo(404);
        assertThat(api().post("/api/rides/" + done.rideId() + "/rating", done.driver(), Map.of("stars", 5))
                .getStatusCode().value()).isEqualTo(403);
        assertThat(api().post("/api/rides/" + done.rideId() + "/rating", done.rider(), Map.of("stars", 6))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(api().post("/api/rides/" + done.rideId() + "/rating", done.rider(), Map.of("stars", 3, "comment", "x".repeat(281)))
                .getStatusCode().value()).isEqualTo(400);
    }
}
