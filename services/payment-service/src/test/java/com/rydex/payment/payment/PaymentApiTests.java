package com.rydex.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.rydex.events.PaymentPayload.Status;
import com.rydex.payment.IntegrationTest;
import com.rydex.payment.KafkaProbe;
import com.rydex.payment.TestTokens;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Reading payments, and admin refunds (mock provider). */
class PaymentApiTests extends IntegrationTest {

    @Test
    void theRiderTheDriverAndAdminsSeeAPaymentNobodyElse() {
        TestRide ride = newRide();
        complete(ride, "339.00");
        awaitStatus(ride.id(), Status.SUCCEEDED);
        String path = "/api/payments/rides/" + ride.id();

        var asRider = api().get(path, TestTokens.rider(ride.riderId()));
        assertThat(asRider.getStatusCode().value()).isEqualTo(200);
        assertThat(asRider.getBody().path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(asRider.getBody().path("kind").asString()).isEqualTo("RIDE_FARE");
        assertThat(asRider.getBody().path("amount").decimalValue()).isEqualByComparingTo("339");
        assertThat(asRider.getBody().path("refundable").decimalValue()).isEqualByComparingTo("339");
        assertThat(asRider.getBody().path("checkout").isNull()).as("nothing to pay").isTrue();

        assertThat(api().get(path, TestTokens.driver(ride.driverId())).getStatusCode().value()).isEqualTo(200);
        assertThat(api().get(path, TestTokens.admin()).getStatusCode().value()).isEqualTo(200);
        assertThat(api().get(path, TestTokens.rider(UUID.randomUUID())).getStatusCode().value()).isEqualTo(404);
        assertThat(api().get(path, null).getStatusCode().value()).isEqualTo(401);
        assertThat(api().get("/api/payments/rides/" + UUID.randomUUID(), TestTokens.admin()).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void ridersAndDriversListTheirOwnPayments() {
        TestRide first = newRide();
        TestRide second = new TestRide(UUID.randomUUID(), first.riderId(), UUID.randomUUID());
        complete(first, "100.00");
        complete(second, "200.00");
        awaitStatus(second.id(), Status.SUCCEEDED);

        var mine = api().get("/api/payments/mine", TestTokens.rider(first.riderId()));
        assertThat(mine.getBody().path("total").asLong()).isEqualTo(2);
        assertThat(mine.getBody().path("items").get(0).path("rideId").asString())
                .as("newest first").isEqualTo(second.id().toString());

        var driver = api().get("/api/payments/mine", TestTokens.driver(first.driverId()));
        assertThat(driver.getBody().path("total").asLong()).isEqualTo(1);
        assertThat(driver.getBody().path("items").get(0).path("amount").decimalValue()).isEqualByComparingTo("100");
    }

    @Test
    void adminRefundsInFull() {
        TestRide ride = newRide();
        Payment p = complete(ride, "339.00");
        awaitStatus(ride.id(), Status.SUCCEEDED);

        var res = api().post("/api/admin/payments/" + p.getId() + "/refunds", TestTokens.admin(),
                Map.of("reason", "Driver took a long detour"));
        assertThat(res.getStatusCode().value()).isEqualTo(202);

        Payment refunded = awaitStatus(ride.id(), Status.REFUNDED);
        assertThat(refunded.getRefundedAmount()).isEqualByComparingTo("339");
        assertThat(refunded.getRefundingAmount()).isEqualByComparingTo("0");

        var events = paymentEvents(ride.id(), 2);
        assertThat(events).extracting(r -> KafkaProbe.header(r, "eventType"))
                .containsExactly("PAYMENT_SUCCEEDED", "PAYMENT_REFUNDED");
        var refundEvent = parse(events.get(1)).payload();
        assertThat(refundEvent.refundAmount()).isEqualByComparingTo("339");
        assertThat(refundEvent.refundedAmount()).isEqualByComparingTo("339");
        assertThat(refundEvent.status()).isEqualTo(Status.REFUNDED);
        assertThat(refundEvent.reason()).isEqualTo("Driver took a long detour");

        var view = api().get("/api/payments/" + p.getId(), TestTokens.rider(ride.riderId())).getBody();
        assertThat(view.path("refunds")).hasSize(1);
        assertThat(view.path("refunds").get(0).path("status").asString()).isEqualTo("PROCESSED");

        var again = api().post("/api/admin/payments/" + p.getId() + "/refunds", TestTokens.admin(),
                Map.of("reason", "Again"));
        assertThat(again.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void partialRefundsAddUpToTheAmountAndNoFurther() {
        TestRide ride = newRide();
        Payment p = complete(ride, "339.00");
        awaitStatus(ride.id(), Status.SUCCEEDED);
        String refunds = "/api/admin/payments/" + p.getId() + "/refunds";

        api().post(refunds, TestTokens.admin(), Map.of("amount", 100, "reason", "Goodwill"));
        await().atMost(WAIT).until(() -> payments.findById(p.getId()).orElseThrow().getRefundedAmount()
                .compareTo(new java.math.BigDecimal("100")) == 0);
        assertThat(payments.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(Status.SUCCEEDED);

        var tooMuch = api().post(refunds, TestTokens.admin(), Map.of("amount", 240, "reason", "Too much"));
        assertThat(tooMuch.getStatusCode().value()).isEqualTo(422);
        assertThat(tooMuch.getBody().path("detail").asString()).contains("239");

        api().post(refunds, TestTokens.admin(), Map.of("amount", 239, "reason", "The rest"));
        Payment done = awaitStatus(ride.id(), Status.REFUNDED);
        assertThat(done.getRefundedAmount()).isEqualByComparingTo("339");
    }

    @Test
    void refundsAreForAdminsAndValidated() {
        TestRide ride = newRide();
        Payment p = complete(ride, "339.00");
        awaitStatus(ride.id(), Status.SUCCEEDED);
        String refunds = "/api/admin/payments/" + p.getId() + "/refunds";

        assertThat(api().post(refunds, TestTokens.rider(ride.riderId()), Map.of("reason", "x")).getStatusCode().value())
                .isEqualTo(403);
        assertThat(api().post(refunds, TestTokens.admin(), Map.of("amount", 10)).getStatusCode().value())
                .as("reason required").isEqualTo(400);
        assertThat(api().post(refunds, TestTokens.admin(), Map.of("amount", 0, "reason", "x")).getStatusCode().value())
                .isEqualTo(400);
        assertThat(api().post(refunds, TestTokens.admin(), Map.of("amount", 1.005, "reason", "x")).getStatusCode()
                .value()).as("paise at most").isEqualTo(400);
        assertThat(api().post("/api/admin/payments/" + UUID.randomUUID() + "/refunds", TestTokens.admin(),
                Map.of("reason", "x")).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void aFailedPaymentCannotBeRefunded() {
        TestRide ride = newRide();
        Payment p = complete(ride, "1500.00");
        awaitStatus(ride.id(), Status.FAILED);

        var res = api().post("/api/admin/payments/" + p.getId() + "/refunds", TestTokens.admin(), Map.of("reason", "x"));

        assertThat(res.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void adminsListPaymentsByStatus() {
        TestRide ride = newRide();
        complete(ride, "1500.00");
        awaitStatus(ride.id(), Status.FAILED);

        var failed = api().get("/api/admin/payments?status=FAILED&size=100", TestTokens.admin()).getBody();

        assertThat(failed.path("items").valueStream().map(i -> i.path("rideId").asString()))
                .contains(ride.id().toString());
        assertThat(failed.path("items").valueStream().map(i -> i.path("status").asString())).containsOnly("FAILED");
    }
}
