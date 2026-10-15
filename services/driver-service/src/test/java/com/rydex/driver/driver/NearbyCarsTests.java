package com.rydex.driver.driver;

import static org.assertj.core.api.Assertions.assertThat;

import com.rydex.common.RideType;
import com.rydex.driver.IntegrationTest;
import com.rydex.driver.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class NearbyCarsTests extends IntegrationTest {

    @Test
    void ridersSeeTheFreeCarsAroundThemWithoutWhoDrivesThem() {
        TestDriver near = onlineDriver(RideType.ECONOMY, north(CENTER, 300));
        onlineDriver(RideType.XL, north(CENTER, 1200));
        onlineDriver(RideType.COMFORT, north(CENTER, 9000)); // too far
        String rider = TestTokens.rider(UUID.randomUUID());

        JsonNode cars = api().get("/api/drivers/nearby?lat=" + CENTER.lat() + "&lng=" + CENTER.lng(), rider).getBody();

        assertThat(cars).hasSize(2);
        assertThat(cars.get(0).path("rideType").asString()).isEqualTo("ECONOMY");
        assertThat(cars.get(0).path("distanceM").asInt()).isBetween(280, 320);
        assertThat(cars.get(1).path("rideType").asString()).isEqualTo("XL");
        // an opaque id, the same on the next poll, and no driver id anywhere
        String id = cars.get(0).path("id").asString();
        assertThat(id).hasSize(16).doesNotContain(near.id().toString());
        assertThat(cars.toString()).doesNotContain(near.id().toString());
        JsonNode again = api().get("/api/drivers/nearby?lat=" + CENTER.lat() + "&lng=" + CENTER.lng(), rider).getBody();
        assertThat(again.get(0).path("id").asString()).isEqualTo(id);
    }

    @Test
    void driversOnATripOrOfflineAreNotShown() {
        TestDriver driver = onlineDriver(RideType.ECONOMY, north(CENTER, 300));
        api().patch("/api/drivers/me/status", driver.token(), java.util.Map.of("status", "OFFLINE"));

        JsonNode cars = api().get("/api/drivers/nearby?lat=" + CENTER.lat() + "&lng=" + CENTER.lng(),
                TestTokens.rider(UUID.randomUUID())).getBody();

        assertThat(cars).isEmpty();
        assertThat(api().get("/api/drivers/nearby?lat=" + CENTER.lat() + "&lng=" + CENTER.lng(), null)
                .getStatusCode().value()).isEqualTo(401);
        assertThat(api().get("/api/drivers/nearby?lat=95&lng=" + CENTER.lng(), TestTokens.rider(UUID.randomUUID()))
                .getStatusCode().value()).isEqualTo(400);
    }
}
