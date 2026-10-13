package com.rydex.payment.razorpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.rydex.events.PaymentPayload.Kind;
import com.rydex.events.PaymentPayload.Provider;
import com.rydex.events.PaymentPayload.Status;
import com.rydex.payment.IntegrationTest;
import com.rydex.payment.KafkaProbe;
import com.rydex.payment.RazorpayStub;
import com.rydex.payment.TestTokens;
import com.rydex.payment.payment.Payment;
import com.rydex.payment.payment.PaymentLedger;
import com.rydex.payment.payment.PaymentProcessor;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Razorpay test mode against {@link RazorpayStub}: orders, the checkout callback, reconciliation
 * without it, webhooks and refunds. Payments are created as a ride event would create them while
 * Razorpay is the active provider.
 */
class RazorpayFlowTests extends IntegrationTest {

    @Autowired
    PaymentLedger ledger;

    @Autowired
    PaymentProcessor processor;

    @Test
    void anOrderIsCreatedAndOnlyTheRiderGetsCheckoutOptions() {
        TestRide ride = newRide();

        Payment p = awaitingRider(ride, "339.00");

        assertThat(p.getStatus()).isEqualTo(Status.PENDING);
        assertThat(p.getProviderRef()).startsWith("order_");
        Map<String, Object> order = RazorpayStub.order(p.getProviderRef());
        assertThat(order.get("amount")).isEqualTo(33900L);
        assertThat(order.get("currency")).isEqualTo("INR");
        assertThat(order.get("receipt")).isEqualTo(p.getId().toString());
        var initiated = paymentEvents(ride.id(), 1).getFirst();
        assertThat(KafkaProbe.header(initiated, "eventType")).isEqualTo("PAYMENT_INITIATED");
        assertThat(parse(initiated).payload().providerRef()).isEqualTo(p.getProviderRef());

        var checkout = api().get("/api/payments/rides/" + ride.id(), TestTokens.rider(ride.riderId()))
                .getBody().path("checkout");
        assertThat(checkout.path("keyId").asString()).isEqualTo(RazorpayStub.KEY_ID);
        assertThat(checkout.path("orderId").asString()).isEqualTo(p.getProviderRef());
        assertThat(checkout.path("amount").asLong()).isEqualTo(33900);
        assertThat(checkout.path("currency").asString()).isEqualTo("INR");
        assertThat(checkout.path("description").asString()).startsWith("Ride fare");
        assertThat(api().get("/api/payments/rides/" + ride.id(), TestTokens.driver(ride.driverId()))
                .getBody().path("checkout").isNull()).isTrue();
    }

    @Test
    void theCheckoutCallbackConfirmsThePayment() {
        TestRide ride = newRide();
        Payment p = awaitingRider(ride, "339.00");
        String payId = RazorpayStub.pay(p.getProviderRef(), "captured", null);

        var res = verify(ride, p, payId, signature(p.getProviderRef(), payId));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(res.getBody().path("checkout").isNull()).isTrue();
        Payment paid = payments.findById(p.getId()).orElseThrow();
        assertThat(paid.getProviderPaymentId()).isEqualTo(payId);
        assertThat(paymentEvents(ride.id(), 2)).extracting(r -> KafkaProbe.header(r, "eventType"))
                .containsExactly("PAYMENT_INITIATED", "PAYMENT_SUCCEEDED");

        // the browser may send it twice
        assertThat(verify(ride, p, payId, signature(p.getProviderRef(), payId)).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void anAuthorizedPaymentIsCaptured() {
        TestRide ride = newRide();
        Payment p = awaitingRider(ride, "120.50");
        String payId = RazorpayStub.pay(p.getProviderRef(), "authorized", null);

        var res = verify(ride, p, payId, signature(p.getProviderRef(), payId));

        assertThat(res.getBody().path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(RazorpayStub.received()).anySatisfy(r -> {
            assertThat(r.path()).isEqualTo("/payments/" + payId + "/capture");
            assertThat(r.body().path("amount").asLong()).isEqualTo(12050);
        });
    }

    @Test
    void forgedOrMismatchedCallbacksAreRejected() {
        TestRide ride = newRide();
        Payment p = awaitingRider(ride, "339.00");
        String payId = RazorpayStub.pay(p.getProviderRef(), "captured", null);

        assertThat(verify(ride, p, payId, "0".repeat(64)).getStatusCode().value()).isEqualTo(400);
        assertThat(api().post("/api/payments/" + p.getId() + "/razorpay/verify", TestTokens.rider(ride.riderId()),
                Map.of("razorpayOrderId", "order_other", "razorpayPaymentId", payId,
                        "razorpaySignature", signature("order_other", payId))).getStatusCode().value()).isEqualTo(400);
        assertThat(api().post("/api/payments/" + p.getId() + "/razorpay/verify", TestTokens.rider(UUID.randomUUID()),
                Map.of("razorpayOrderId", p.getProviderRef(), "razorpayPaymentId", payId,
                        "razorpaySignature", signature(p.getProviderRef(), payId))).getStatusCode().value())
                .isEqualTo(404);
        // a validly signed attempt of another order cannot pay this one
        Payment other = awaitingRider(newRide(), "10.00");
        String otherPay = RazorpayStub.pay(other.getProviderRef(), "captured", null);
        assertThat(api().post("/api/payments/" + p.getId() + "/razorpay/verify", TestTokens.rider(ride.riderId()),
                Map.of("razorpayOrderId", p.getProviderRef(), "razorpayPaymentId", otherPay,
                        "razorpaySignature", signature(p.getProviderRef(), otherPay))).getStatusCode().value())
                .isEqualTo(400);

        assertThat(payments.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(Status.PENDING);
    }

    @Test
    void aPaymentIsNoticedEvenWithoutTheCallback() {
        TestRide ride = newRide();
        Payment p = awaitingRider(ride, "339.00");

        String payId = RazorpayStub.pay(p.getProviderRef(), "captured", null); // the rider closed the tab

        Payment paid = awaitStatus(ride.id(), Status.SUCCEEDED);
        assertThat(paid.getProviderPaymentId()).isEqualTo(payId);
    }

    @Test
    void aFailedAttemptFailsThePaymentUntilALaterOneSucceeds() {
        TestRide ride = newRide();
        Payment p = awaitingRider(ride, "339.00");

        RazorpayStub.pay(p.getProviderRef(), "failed", "Payment declined by the bank");
        Payment failed = awaitStatus(ride.id(), Status.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo("Payment declined by the bank");
        assertThat(failed.getNextAttemptAt()).as("still watching the order").isNotNull();

        RazorpayStub.pay(p.getProviderRef(), "captured", null);
        awaitStatus(ride.id(), Status.SUCCEEDED);
        assertThat(paymentEvents(ride.id(), 3)).extracting(r -> KafkaProbe.header(r, "eventType"))
                .containsExactly("PAYMENT_INITIATED", "PAYMENT_FAILED", "PAYMENT_SUCCEEDED");
    }

    @Test
    void ordersAreRetriedWhileRazorpayIsDown() {
        RazorpayStub.unavailableFor(2);
        TestRide ride = newRide();

        Payment p = awaitingRider(ride, "339.00");

        assertThat(p.getAttempts()).isGreaterThanOrEqualTo(3);
        assertThat(p.getStatus()).isEqualTo(Status.PENDING);
    }

    @Test
    void signedWebhooksConfirmPaymentsAndOthersAreRejected() {
        TestRide ride = newRide();
        Payment p = awaitingRider(ride, "339.00");
        String payId = RazorpayStub.pay(p.getProviderRef(), "captured", null);
        String body = """
                {"entity":"event","event":"payment.captured","payload":{"payment":{"entity":
                {"id":"%s","order_id":"%s","amount":33900,"currency":"INR","status":"captured"}}}}
                """.formatted(payId, p.getProviderRef());

        assertThat(webhook(body, "bad").value()).isEqualTo(400);
        assertThat(payments.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(Status.PENDING);

        assertThat(webhook(body, RazorpaySignatures.hmacHex(body, RazorpayStub.WEBHOOK_SECRET)).value()).isEqualTo(200);
        assertThat(payments.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(Status.SUCCEEDED);

        String unknown = body.replace(p.getProviderRef(), "order_not_ours");
        assertThat(webhook(unknown, RazorpaySignatures.hmacHex(unknown, RazorpayStub.WEBHOOK_SECRET)).value())
                .as("acknowledged, so Razorpay stops retrying").isEqualTo(200);
    }

    @Test
    void refundsGoThroughRazorpay() {
        TestRide ride = newRide();
        Payment p = paidWithRazorpay(ride, "339.00");

        api().post("/api/admin/payments/" + p.getId() + "/refunds", TestTokens.admin(),
                Map.of("amount", 100, "reason", "Goodwill"));

        await().atMost(WAIT).until(() -> payments.findById(p.getId()).orElseThrow().getRefundedAmount()
                .compareTo(new BigDecimal("100")) == 0);
        var refund = RazorpayStub.refunds().getFirst();
        assertThat(refund.get("payment_id")).isEqualTo(p.getProviderPaymentId());
        assertThat(refund.get("amount")).isEqualTo(10000L);
        assertThat(paymentEvents(ride.id(), 3).get(2)).satisfies(r ->
                assertThat(KafkaProbe.header(r, "eventType")).isEqualTo("PAYMENT_REFUNDED"));
    }

    @Test
    void aPendingRefundIsFollowedUntilProcessed() {
        RazorpayStub.refundStatus("pending");
        TestRide ride = newRide();
        Payment p = paidWithRazorpay(ride, "339.00");

        api().post("/api/admin/payments/" + p.getId() + "/refunds", TestTokens.admin(), Map.of("reason", "Full"));
        await().atMost(WAIT).until(() -> RazorpayStub.refunds().size() == 1);
        assertThat(payments.findById(p.getId()).orElseThrow().getRefundingAmount()).isEqualByComparingTo("339");

        RazorpayStub.settleRefund((String) RazorpayStub.refunds().getFirst().get("id"), "processed");
        Payment refunded = awaitStatus(ride.id(), Status.REFUNDED);
        assertThat(refunded.getRefundingAmount()).isEqualByComparingTo("0");
    }

    @Test
    void aRefundWhoseAnswerWasLostIsNotMadeTwice() {
        TestRide ride = newRide();
        Payment p = paidWithRazorpay(ride, "339.00");
        RazorpayStub.loseNextRefundResponse();

        api().post("/api/admin/payments/" + p.getId() + "/refunds", TestTokens.admin(), Map.of("reason", "Full"));

        awaitStatus(ride.id(), Status.REFUNDED);
        assertThat(RazorpayStub.refunds()).hasSize(1);
    }

    /** A RAZORPAY payment, as a ride event creates it while Razorpay takes payments, with its order created. */
    private Payment awaitingRider(TestRide ride, String amount) {
        Payment created = ledger.create(ride.id(), ride.riderId(), ride.driverId(), Kind.RIDE_FARE,
                new BigDecimal(amount), Provider.RAZORPAY);
        processor.kick(created.getId());
        return await().atMost(WAIT).until(() -> payments.findById(created.getId()).orElseThrow(),
                p -> p.getProviderRef() != null);
    }

    private Payment paidWithRazorpay(TestRide ride, String amount) {
        Payment p = awaitingRider(ride, amount);
        String payId = RazorpayStub.pay(p.getProviderRef(), "captured", null);
        verify(ride, p, payId, signature(p.getProviderRef(), payId));
        return awaitStatus(ride.id(), Status.SUCCEEDED);
    }

    private ResponseEntity<JsonNode> verify(TestRide ride, Payment p, String payId, String signature) {
        return api().post("/api/payments/" + p.getId() + "/razorpay/verify", TestTokens.rider(ride.riderId()),
                Map.of("razorpayOrderId", p.getProviderRef(), "razorpayPaymentId", payId,
                        "razorpaySignature", signature));
    }

    private static String signature(String orderId, String payId) {
        return RazorpaySignatures.hmacHex(orderId + "|" + payId, RazorpayStub.KEY_SECRET);
    }

    private HttpStatusCode webhook(String body, String signature) {
        return RestClient.create("http://localhost:" + port).post().uri("/api/payments/webhooks/razorpay")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Razorpay-Signature", signature)
                .body(body)
                .retrieve()
                .onStatus(s -> true, (req, res) -> { })
                .toBodilessEntity()
                .getStatusCode();
    }
}
