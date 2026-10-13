package com.rydex.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class PaymentServiceApplicationTests extends IntegrationTest {

    @Test
    void healthEndpointReportsUp() {
        var res = api().get("/actuator/health", null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().path("status").asString()).isEqualTo("UP");
    }

    @Test
    void paymentEndpointsNeedAToken() {
        assertThat(api().get("/api/payments/mine", null).getStatusCode().value()).isEqualTo(401);
        assertThat(api().get("/api/admin/payments", TestTokens.rider(java.util.UUID.randomUUID()))
                .getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void configIsPublicAndNamesTheActiveProvider() {
        var res = api().get("/api/payments/config", null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().path("provider").asString()).isEqualTo("MOCK");
        assertThat(res.getBody().path("currency").asString()).isEqualTo("INR");
        // the key id is only handed out while Razorpay takes new payments
        assertThat(res.getBody().path("razorpayKeyId").isNull()).isTrue();
    }

    @Test
    void servesApiDocsAndTheDevCheckoutPage() {
        assertThat(api().get("/v3/api-docs", null).getStatusCode().value()).isEqualTo(200);
        String page = RestClient.create("http://localhost:" + port).get().uri("/dev/checkout.html")
                .retrieve().body(String.class);
        assertThat(page).contains("checkout.razorpay.com/v1/checkout.js");
    }
}
