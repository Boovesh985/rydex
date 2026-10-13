package com.rydex.payment.razorpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rydex.payment.config.PaymentProperties;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RazorpayUnitTest {

    @Test
    void hmacMatchesTheRfc4231TestVector() {
        assertThat(RazorpaySignatures.hmacHex("what do ya want for nothing?", "Jefe"))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    void checkoutSignaturesCoverTheOrderAndThePayment() {
        String sig = RazorpaySignatures.hmacHex("order_1|pay_1", "secret");

        assertThat(RazorpaySignatures.checkoutValid("order_1", "pay_1", sig, "secret")).isTrue();
        assertThat(RazorpaySignatures.checkoutValid("order_1", "pay_1", sig.toUpperCase(), "secret")).isTrue();
        assertThat(RazorpaySignatures.checkoutValid("order_1", "pay_2", sig, "secret")).isFalse();
        assertThat(RazorpaySignatures.checkoutValid("order_1", "pay_1", sig, "other")).isFalse();
        assertThat(RazorpaySignatures.checkoutValid("order_1", "pay_1", null, "secret")).isFalse();
    }

    @Test
    void webhookSignaturesCoverTheRawBody() {
        String body = "{\"event\":\"payment.captured\"}";
        String sig = RazorpaySignatures.hmacHex(body, "wh");

        assertThat(RazorpaySignatures.webhookValid(body, sig, "wh")).isTrue();
        assertThat(RazorpaySignatures.webhookValid(body + " ", sig, "wh")).isFalse();
    }

    @Test
    void amountsAreSentInPaise() {
        assertThat(RazorpayProvider.paise(new BigDecimal("339.00"))).isEqualTo(33900);
        assertThat(RazorpayProvider.paise(new BigDecimal("120.5"))).isEqualTo(12050);
        assertThat(RazorpayProvider.paise(new BigDecimal("50"))).isEqualTo(5000);
        assertThatThrownBy(() -> RazorpayProvider.paise(new BigDecimal("1.005"))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void onlyTestKeysAreAccepted() {
        assertThatThrownBy(() -> keys(null, null, false).validate()).hasMessageContaining("RAZORPAY_KEY_ID");
        assertThatThrownBy(() -> keys("rzp_test_abc", " ", false).validate()).hasMessageContaining("RAZORPAY_KEY_SECRET");
        assertThatThrownBy(() -> keys("rzp_live_abc", "s", false).validate()).hasMessageContaining("not a test key");
        keys("rzp_test_abc", "s", false).validate();
        keys("rzp_live_abc", "s", true).validate();
    }

    private static PaymentProperties.Razorpay keys(String id, String secret, boolean allowLive) {
        return new PaymentProperties.Razorpay("https://api.razorpay.com/v1", id, secret, null, allowLive, "Rydex",
                Duration.ofSeconds(3), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(30),
                Duration.ofDays(7));
    }
}
