package com.rydex.ride.ride;

import static com.rydex.ride.TestApi.token;
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

/** The safety nets for bookings that never get a matching outcome, and for booking retries. */
class UnmatchedRideTests extends IntegrationTest {

    static final Map<String, Object> PICKUP = Map.of("lat", 13.0827, "lng", 80.2707, "address", "Chennai Central");
    static final Map<String, Object> DROP = Map.of("lat", 13.0500, "lng", 80.2824, "address", "Marina Beach");

    @Autowired
    UnmatchedRideSweeper sweeper;

    @Autowired
    IdempotencyStore idempotency;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void stubRouting() {
        given(osrm.route(any(), any())).willReturn(List.of(new RouteResult.Alternative(5_000, 900, "poly")));
    }

    private record Booked(UUID rideId, String riderToken) {
    }

    private Booked book() {
        String rider = token(api().register("RIDER"));
        var res = api().post("/api/rides", rider, Map.of("pickup", PICKUP, "drop", DROP, "rideType", "ECONOMY"));
        assertThat(res.getStatusCode().value()).as("book: %s", res.getBody()).isEqualTo(201);
        return new Booked(UUID.fromString(res.getBody().path("id").asString()), rider);
    }

    private void backdate(Booked b, Duration by) {
        jdbc.sql("UPDATE rides SET requested_at = requested_at - make_interval(secs => :s) WHERE id = :id")
                .param("s", by.toSeconds()).param("id", b.rideId()).update();
    }

    private JsonNode ride(Booked b) {
        return api().get("/api/rides/" + b.rideId(), b.riderToken()).getBody();
    }

    @Test
    void rideWithNoMatchingOutcomeIsCancelledBySystemAndAnnounced() {
        Booked stuck = book();
        Booked fresh = book();
        backdate(stuck, Duration.ofHours(2)); // timeout is 1h in tests

        assertThat(sweeper.sweep()).isGreaterThanOrEqualTo(1);

        JsonNode ride = ride(stuck);
        assertThat(ride.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(ride.path("cancelledBy").asString()).isEqualTo("SYSTEM");
        assertThat(ride.path("cancelReason").asString()).isEqualTo("No driver found in time");
        assertThat(ride.path("timeline").get(1).path("to").asString()).isEqualTo("CANCELLED");
        assertThat(ride(fresh).path("status").asString()).isEqualTo("REQUESTED");
        // announced like any cancellation, so driver-service stops matching and frees any driver
        var events = kafka().await(Topics.RIDE_EVENTS, r -> stuck.rideId().toString().equals(r.key()), 2, Duration.ofSeconds(30));
        assertThat(events).extracting(r -> KafkaProbe.header(r, "eventType")).containsExactly("RIDE_REQUESTED", "RIDE_CANCELLED");
        // and the rider is free to book again
        var again = api().post("/api/rides", stuck.riderToken(), Map.of("pickup", PICKUP, "drop", DROP, "rideType", "XL"));
        assertThat(again.getStatusCode().value()).isEqualTo(201);
        // a second sweep leaves it alone
        assertThat(ride(stuck).path("status").asString()).isEqualTo("CANCELLED");
        assertThat(sweeper.sweep()).isZero();
    }

    @Test
    void anInterruptedBookingFreesItsIdempotencyKeyWithinAMinute() {
        UUID rider = UUID.randomUUID();
        String key = "retry-" + UUID.randomUUID();

        assertThat(idempotency.begin(rider, key)).isEmpty();

        // the claim alone (what a crash mid-booking leaves behind) expires quickly ...
        Long pendingTtl = redis.getExpire("idem:" + rider + ":" + key);
        assertThat(pendingTtl).isBetween(1L, 60L);
        // ... while a completed booking is remembered for a day
        idempotency.complete(rider, key, UUID.randomUUID());
        assertThat(redis.getExpire("idem:" + rider + ":" + key)).isGreaterThan(Duration.ofHours(23).toSeconds());
    }

    @Test
    void sweeperIgnoresRidesThatAlreadyHaveAnOutcome() {
        Booked b = book();
        api().post("/api/rides/" + b.rideId() + "/cancel", b.riderToken(), Map.of("reason", "changed plans"));
        backdate(b, Duration.ofHours(2));

        sweeper.sweep();

        assertThat(ride(b).path("cancelledBy").asString()).isEqualTo("RIDER");
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE aggregate_id = :id AND event_type = 'RIDE_CANCELLED'")
                .param("id", b.rideId().toString()).query(Integer.class).single()).isEqualTo(1);
    }
}
