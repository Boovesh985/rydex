package com.rydex.ride.ride;

import static com.rydex.ride.TestApi.token;
import static com.rydex.ride.TestApi.userId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.rydex.common.RedisKeys;
import com.rydex.events.Topics;
import com.rydex.ride.IntegrationTest;
import com.rydex.ride.KafkaProbe;
import com.rydex.ride.geo.RouteResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** The safety net for rides under way whose driver vanished. */
class AbandonedRideTests extends IntegrationTest {

    static final Map<String, Object> PICKUP = Map.of("lat", 13.0827, "lng", 80.2707, "address", "Chennai Central");
    static final Map<String, Object> DROP = Map.of("lat", 13.0500, "lng", 80.2824, "address", "Marina Beach");

    @Autowired
    AbandonedRideSweeper sweeper;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void stubRouting() {
        given(osrm.route(any(), any())).willReturn(List.of(new RouteResult.Alternative(5_000, 900, "poly")));
    }

    private record Booked(UUID rideId, String riderToken, UUID driverId) {
    }

    /** A ride whose trip started {@code ago} (assigned and arrived just before), with its own driver. */
    private Booked underWay(Duration ago) {
        String rider = token(api().register("RIDER"));
        var res = api().post("/api/rides", rider, Map.of("pickup", PICKUP, "drop", DROP, "rideType", "BIKE"));
        assertThat(res.getStatusCode().value()).as("book: %s", res.getBody()).isEqualTo(201);
        UUID rideId = UUID.fromString(res.getBody().path("id").asString());
        UUID driverId = userId(api().register("DRIVER"));
        jdbc.sql("""
                        UPDATE rides SET status = 'IN_PROGRESS', driver_id = :driver,
                               assigned_at = now() - make_interval(secs => :s + 600),
                               arrived_at = now() - make_interval(secs => :s + 60),
                               started_at = now() - make_interval(secs => :s)
                        WHERE id = :id""")
                .param("driver", driverId).param("s", ago.toSeconds()).param("id", rideId).update();
        return new Booked(rideId, rider, driverId);
    }

    private void heard(Booked b, Duration ago) {
        redis.opsForZSet().add(RedisKeys.driverHeard(), b.driverId().toString(), Instant.now().minus(ago).toEpochMilli());
    }

    private JsonNode ride(Booked b) {
        return api().get("/api/rides/" + b.rideId(), b.riderToken()).getBody();
    }

    @Test
    void aTripWhoseDriverWentSilentIsEndedFreeAndAnnounced() {
        Booked vanished = underWay(Duration.ofMinutes(40));
        heard(vanished, Duration.ofMinutes(30));

        assertThat(sweeper.sweep()).isGreaterThanOrEqualTo(1);

        JsonNode ride = ride(vanished);
        assertThat(ride.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(ride.path("cancelledBy").asString()).isEqualTo("SYSTEM");
        assertThat(ride.path("cancelReason").asString()).contains("stopped responding").contains("not charged");
        assertThat(ride.path("cancellationFee").isNull()).isTrue();
        // announced like any cancellation: driver-service frees the driver, the rider and driver are told
        var events = kafka().await(Topics.RIDE_EVENTS, r -> vanished.rideId().toString().equals(r.key()), 2, Duration.ofSeconds(30));
        assertThat(events).extracting(r -> KafkaProbe.header(r, "eventType")).containsExactly("RIDE_REQUESTED", "RIDE_CANCELLED");
        // and the rider can book again
        var again = api().post("/api/rides", vanished.riderToken(), Map.of("pickup", PICKUP, "drop", DROP, "rideType", "AUTO"));
        assertThat(again.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void aLongTripWithItsDriverStillThereGoesOn() {
        Booked longTrip = underWay(Duration.ofMinutes(40));
        heard(longTrip, Duration.ofSeconds(3));
        Booked justStarted = underWay(Duration.ofMinutes(2)); // never heard from, but too recent to judge

        sweeper.sweep();

        assertThat(ride(longTrip).path("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(ride(justStarted).path("status").asString()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void aDriverWithNoHeartbeatOnRecordIsJudgedByHowLongTheRideStoodStill() {
        // no driver:heard entry (a record from before it existed): 40 min proves nothing, 3 h does
        Booked recent = underWay(Duration.ofMinutes(40));
        Booked forgotten = underWay(Duration.ofHours(3));

        sweeper.sweep();

        assertThat(ride(recent).path("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(ride(forgotten).path("status").asString()).isEqualTo("CANCELLED");
    }

    @Test
    void ridersStillCannotCancelATripUnderWay() {
        Booked trip = underWay(Duration.ofMinutes(1));

        var res = api().post("/api/rides/" + trip.rideId() + "/cancel", trip.riderToken(), Map.of("reason", "let me out"));

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(ride(trip).path("status").asString()).isEqualTo("IN_PROGRESS");
    }
}
