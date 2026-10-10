package com.rydex.ride;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RideServiceApplicationTests extends IntegrationTest {

    @Test
    void healthEndpointReportsUp() {
        var res = api().get("/actuator/health", null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().path("status").asString()).isEqualTo("UP");
    }

    @Test
    void openApiDocumentIsPublished() {
        var res = api().get("/v3/api-docs", null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().path("paths").has("/api/rides")).isTrue();
        assertThat(res.getBody().path("paths").has("/api/auth/login")).isTrue();
    }
}
