package com.rydex.ride.ride;

import static com.rydex.ride.TestApi.token;
import static com.rydex.ride.TestApi.userId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.rydex.ride.IntegrationTest;
import com.rydex.ride.geo.RouteResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Cancelling is free until the driver arrives; after that the rider pays the ride type's fee (ADR 0012). */
class CancellationFeeTests extends IntegrationTest {

    static final Map<String, Object> PICKUP = Map.of("lat", 13.0827, "lng", 80.2707, "address", "Chennai Central");
    static final Map<String, Object> DROP = Map.of("lat", 13.0500, "lng", 80.2824, "address", "Marina Beach");

    @Autowired
    RideService rideService;

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

    private Trip assignedTrip(String rideType) {
        String rider = token(api().register("RIDER"));
        var driverAuth = api().register("DRIVER");
        var res = api().call(HttpMethod.POST, "/api/rides", rider,
                Map.of("pickup", PICKUP, "drop", DROP, "rideType", rideType),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        UUID rideId = UUID.fromString(res.getBody().path("id").asString());
        rideService.assignDriver(rideId, userId(driverAuth), "SYSTEM:test");
        return new Trip(rideId, rider, token(driverAuth));
    }

    private JsonNode cancelledEvent(UUID rideId) {
        String payload = jdbc.sql("""
                        SELECT payload::text FROM outbox WHERE aggregate_id = :id AND event_type = 'RIDE_CANCELLED'
                        """)
                .param("id", rideId.toString()).query(String.class).single();
        return json.readTree(payload).path("payload");
    }

    @Test
    void cancellingAfterTheDriverArrivedCostsTheFee() {
        Trip trip = assignedTrip("COMFORT");
        api().post("/api/rides/" + trip.rideId() + "/arrived", trip.driver(), null);

        var res = api().post("/api/rides/" + trip.rideId() + "/cancel", trip.rider(), Map.of("reason", "Changed plans"));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().path("cancellationFee").decimalValue()).isEqualByComparingTo("75");
        assertThat(cancelledEvent(trip.rideId()).path("cancellationFee").decimalValue()).isEqualByComparingTo("75");
        assertThat(api().get("/api/rides/" + trip.rideId(), trip.rider()).getBody().path("cancellationFee")
                .decimalValue()).isEqualByComparingTo("75");
    }

    @Test
    void cancellingBeforeTheDriverArrivedIsFree() {
        Trip trip = assignedTrip("ECONOMY");

        var res = api().post("/api/rides/" + trip.rideId() + "/cancel", trip.rider(), null);

        assertThat(res.getBody().path("status").asString()).isEqualTo("CANCELLED");
        assertThat(res.getBody().path("cancellationFee").isNull()).isTrue();
        assertThat(cancelledEvent(trip.rideId()).path("cancellationFee").isNull()).isTrue();
    }

    @Test
    void theRiderPaysNothingWhenTheDriverCancels() {
        Trip trip = assignedTrip("XL");
        api().post("/api/rides/" + trip.rideId() + "/arrived", trip.driver(), null);

        var res = api().post("/api/rides/" + trip.rideId() + "/cancel", trip.driver(), Map.of("reason", "Rider no-show"));

        assertThat(res.getBody().path("cancelledBy").asString()).isEqualTo("DRIVER");
        assertThat(res.getBody().path("cancellationFee").isNull()).isTrue();
    }
}
