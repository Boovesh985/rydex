package com.rydex.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rydex.events.PaymentPayload.Kind;
import com.rydex.events.PaymentPayload.Provider;
import com.rydex.events.PaymentPayload.Status;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentRulesTest {

    private static final Instant T0 = Instant.parse("2026-10-13T10:00:00Z");

    @Test
    void backoffDoublesUpToTheMaximum() {
        Duration base = Duration.ofSeconds(1);
        Duration max = Duration.ofMinutes(5);

        assertThat(PaymentLedger.backoff(base, max, 0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(PaymentLedger.backoff(base, max, 3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(PaymentLedger.backoff(base, max, 9)).isEqualTo(max);
        assertThat(PaymentLedger.backoff(base, max, 500)).isEqualTo(max);
        assertThat(PaymentLedger.backoff(base, max, -1)).isEqualTo(base);
    }

    @Test
    void confirmationsAreIdempotentAndWinOverAFailure() {
        Payment p = payment(Provider.RAZORPAY);
        p.awaitCustomer("order_1", T0, T0);

        assertThat(p.declined("order_1", "bank said no", T0, T0)).isTrue();
        assertThat(p.declined("order_1", "again", T0, T0)).as("already failed").isFalse();
        assertThat(p.awaitsCustomer()).isTrue();

        assertThat(p.charged("order_1", "pay_2", T0)).isTrue();
        assertThat(p.charged("order_1", "pay_2", T0)).isFalse();
        assertThat(p.getStatus()).isEqualTo(Status.SUCCEEDED);
        assertThat(p.getFailureReason()).isNull();
        assertThat(p.awaitsCustomer()).isFalse();
    }

    @Test
    void refundsCannotExceedWhatWasPaid() {
        Payment p = payment(Provider.MOCK);
        assertThat(p.refundable()).as("nothing paid yet").isEqualByComparingTo("0");
        p.charged("mock_1", null, T0);

        p.reserveRefund(new BigDecimal("300"), T0);
        assertThat(p.refundable()).isEqualByComparingTo("39");
        assertThatThrownBy(() -> p.reserveRefund(new BigDecimal("40"), T0)).isInstanceOf(IllegalArgumentException.class);

        p.refundFailed(new BigDecimal("300"), T0);
        assertThat(p.refundable()).isEqualByComparingTo("339");
        p.reserveRefund(new BigDecimal("339"), T0);
        p.refunded(new BigDecimal("339"), T0);
        assertThat(p.getStatus()).isEqualTo(Status.REFUNDED);
        assertThat(p.refundable()).isEqualByComparingTo("0");
    }

    @Test
    void aMockRetryNeedsANewChargeARazorpayRetryKeepsTheOrder() {
        Payment mock = payment(Provider.MOCK);
        mock.declined("mock_1", "declined", null, T0);
        mock.retry(T0);
        assertThat(mock.needsStart()).isTrue();

        Payment razorpay = payment(Provider.RAZORPAY);
        razorpay.awaitCustomer("order_1", T0, T0);
        razorpay.declined("order_1", "declined", T0, T0);
        razorpay.retry(T0);
        assertThat(razorpay.needsStart()).isFalse();
        assertThat(razorpay.awaitsCustomer()).isTrue();

        assertThatThrownBy(() -> razorpay.retry(T0)).isInstanceOf(IllegalStateException.class);
    }

    private static Payment payment(Provider provider) {
        return Payment.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Kind.RIDE_FARE,
                new BigDecimal("339.00"), "INR", provider, T0);
    }
}
