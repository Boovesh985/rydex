package com.rydex.driver.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.rydex.common.RideType;
import com.rydex.driver.IntegrationTest;
import com.rydex.driver.TestTokens;
import com.rydex.events.RidePayload;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@code GET /api/drivers/for-ride/{rideId}}: the rider's driver card. */
class RideDriverCardTests extends IntegrationTest {

    private UUID requestRideFor(UUID riderId) {
        UUID rideId = UUID.randomUUID();
        matching.onRideRequested(rideId, new RidePayload(riderId, null, RideType.COMFORT, CENTER, AIRPORT,
                new BigDecimal("339.00"), 22111, 1359, null), Instant.now());
        return rideId;
    }

    private String path(UUID rideId) {
        return "/api/drivers/for-ride/" + rideId;
    }

    @Test
    void theRiderSeesTheirAssignedDriverAndNobodyElseDoes() {
        TestDriver driver = onlineDriver(RideType.COMFORT, CENTER);
        UUID riderId = UUID.randomUUID();
        UUID rideId = requestRideFor(riderId);

        assertThat(api().get(path(rideId), TestTokens.rider(riderId)).getStatusCode().value())
                .as("no driver yet").isEqualTo(404);

        await().atMost(WAIT).until(() -> currentOffer(driver).getStatusCode().value() == 200);
        assertThat(api().post("/api/drivers/me/offers/" + rideId + "/accept", driver.token(), null)
                .getStatusCode().value()).isEqualTo(200);

        var card = api().get(path(rideId), TestTokens.rider(riderId));
        assertThat(card.getStatusCode().value()).isEqualTo(200);
        assertThat(card.getBody().path("id").asString()).isEqualTo(driver.id().toString());
        assertThat(card.getBody().path("name").asString()).isEqualTo("Test Driver");
        assertThat(card.getBody().path("vehicleType").asString()).isEqualTo("COMFORT");
        assertThat(card.getBody().path("vehicleNumber").asString()).startsWith("TST-");
        assertThat(card.getBody().path("rating").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(card.getBody().has("lastLocation")).isFalse();

        assertThat(api().get(path(rideId), driver.token()).getStatusCode().value()).isEqualTo(200);
        assertThat(api().get(path(rideId), TestTokens.admin()).getStatusCode().value()).isEqualTo(200);
        assertThat(api().get(path(rideId), TestTokens.rider(UUID.randomUUID())).getStatusCode().value()).isEqualTo(404);
        assertThat(api().get(path(rideId), TestTokens.driver(UUID.randomUUID())).getStatusCode().value()).isEqualTo(404);
        assertThat(api().get(path(UUID.randomUUID()), TestTokens.rider(riderId)).getStatusCode().value()).isEqualTo(404);
        assertThat(api().get(path(rideId), null).getStatusCode().value()).isEqualTo(401);
    }
}
