package com.rydex.tracking.ws;

import static com.rydex.tracking.StompTestClient.next;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.rydex.common.ws.WsDestinations;
import com.rydex.events.EventType;
import com.rydex.tracking.IntegrationTest;
import com.rydex.tracking.StompTestClient;
import com.rydex.tracking.StubServices;
import com.rydex.tracking.TestTokens;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Who may connect, subscribe and send (StompAuthInterceptor). */
class WebSocketAuthTests extends IntegrationTest {

    @Autowired
    WsSessionMetrics sessions;

    @Test
    void connectingNeedsAValidAccessToken() {
        assertThatThrownBy(() -> connect(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> connect("not-a-jwt")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> connect(TestTokens.signedByStranger(UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> connect(TestTokens.withIssuer(UUID.randomUUID(), "someone-else")))
                .isInstanceOf(IllegalStateException.class);

        try (StompTestClient rider = connect(TestTokens.rider(UUID.randomUUID()))) {
            assertThat(rider.isConnected()).isTrue();
            await().atMost(WAIT).until(() -> sessions.activeSessions() >= 1);
        }
    }

    @Test
    void onlyTheRiderTheDriverAndAdminsCanWatchARide() throws Exception {
        TestRide ride = newRide();
        publish(EventType.RIDE_REQUESTED, ride);
        publish(EventType.DRIVER_ASSIGNED, ride);
        await().atMost(WAIT).until(() -> "DRIVER_ASSIGNED".equals(rideStatus(ride.id())));
        String topic = WsDestinations.rideLocation(ride.id());

        for (String allowed : new String[] {TestTokens.rider(ride.riderId()), TestTokens.driver(ride.driverId()), TestTokens.admin()}) {
            try (StompTestClient client = connect(allowed)) {
                client.subscribe(topic);
                client.subscribe("/user/queue/notifications");
                Thread.sleep(300); // a refused SUBSCRIBE would have closed the connection by now
                assertThat(client.isConnected()).isTrue();
            }
        }

        try (StompTestClient stranger = connect(TestTokens.rider(UUID.randomUUID()))) {
            stranger.subscribe(topic);
            assertThat(stranger.awaitRefusal()).isEqualTo("ERROR You cannot subscribe to " + topic);
            await().atMost(WAIT).until(() -> !stranger.isConnected()); // and the server hangs up
        }
    }

    @Test
    void aRideNotYetInTheProjectionIsCheckedWithRideService() {
        UUID rideId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        String riderToken = TestTokens.rider(riderId);
        StubServices.allowRide(rideId, riderToken); // ride-service knows the rider booked it

        try (StompTestClient rider = connect(riderToken)) {
            rider.subscribe(WsDestinations.rideLocation(rideId));
            rider.subscribe("/user/queue/errors");
            await().pollDelay(Duration.ofMillis(500)).atMost(WAIT).until(rider::isConnected);
        }
        assertThat(StubServices.received()).anyMatch(r -> r.path().equals("/api/rides/" + rideId)
                && ("Bearer " + riderToken).equals(r.authorization()));

        try (StompTestClient other = connect(TestTokens.rider(UUID.randomUUID()))) {
            other.subscribe(WsDestinations.rideLocation(rideId));
            assertThat(other.awaitRefusal()).contains("ERROR");
        }
    }

    @Test
    void offersAreForDriversAndOnlyDriversSendLocations() {
        try (StompTestClient rider = connect(TestTokens.rider(UUID.randomUUID()))) {
            rider.subscribe("/user/queue/offers");
            assertThat(rider.awaitRefusal()).isEqualTo("ERROR Only drivers can use /user/queue/offers");
        }
        try (StompTestClient rider = connect(TestTokens.rider(UUID.randomUUID()))) {
            rider.send("/app/driver.location", Map.of("lat", 13.08, "lng", 80.27));
            assertThat(rider.awaitRefusal()).contains("ERROR");
        }
        try (StompTestClient driver = connect(TestTokens.driver(UUID.randomUUID()))) {
            driver.subscribe("/topic/everything");
            assertThat(driver.awaitRefusal()).contains("ERROR");
        }
    }

    @Test
    void aSessionWhoseTokenExpiredMustReconnect() throws Exception {
        UUID driverId = UUID.randomUUID();
        try (StompTestClient driver = connect(TestTokens.expiringIn(driverId, "DRIVER", Duration.ofSeconds(2)))) {
            var errors = driver.subscribe("/user/queue/errors");
            driver.send("/app/driver.location", Map.of("lat", 13.08, "lng", 80.27));
            Thread.sleep(2_500);

            driver.send("/app/driver.location", Map.of("lat", 13.081, "lng", 80.27));

            assertThat(driver.awaitRefusal()).isEqualTo("ERROR Access token expired; reconnect with a fresh token");
            assertThat(errors).isEmpty();
        }
        // a fresh token works again
        try (StompTestClient driver = connect(TestTokens.driver(driverId))) {
            var errors = driver.subscribe("/user/queue/errors");
            driver.send("/app/driver.location", Map.of("lat", 200, "lng", 80.27));
            assertThat(next(errors).path("code").asString()).isEqualTo("INVALID_LOCATION");
        }
    }
}
