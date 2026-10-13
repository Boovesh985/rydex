package com.rydex.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TrackingServiceApplicationTests extends IntegrationTest {

    @Test
    void healthEndpointReportsUp() {
        var res = api().get("/actuator/health", null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().path("status").asString()).isEqualTo("UP");
    }

    @Test
    void restEndpointsNeedAToken() {
        assertThat(api().get("/api/tracking/rides/00000000-0000-0000-0000-000000000000/location", null)
                .getStatusCode().value()).isEqualTo(401);
    }
}
