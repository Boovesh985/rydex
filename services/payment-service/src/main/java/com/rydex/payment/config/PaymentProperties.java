package com.rydex.payment.config;

import com.rydex.events.PaymentPayload.Provider;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param provider       which provider charges riders: MOCK (default) or RAZORPAY (test mode)
 * @param processInterval how often due payments and refunds are picked up; new ones start at once
 * @param lease          how long a worker holds a payment or refund while it talks to the provider
 * @param retryMaxDelay  provider unreachable: retries back off from 1 s up to this
 * @param devPages       serve /dev/checkout.html (Razorpay test checkout before the frontend exists)
 */
@ConfigurationProperties("rydex.payments")
public record PaymentProperties(
        @DefaultValue("MOCK") Provider provider,
        @DefaultValue("INR") String currency,
        @DefaultValue("2s") Duration processInterval,
        @DefaultValue("30s") Duration lease,
        @DefaultValue("5m") Duration retryMaxDelay,
        @DefaultValue("false") boolean devPages,
        @DefaultValue Mock mock,
        @DefaultValue Razorpay razorpay) {

    /** @param declineAbove the mock declines charges above this many INR, to exercise failures */
    public record Mock(@DefaultValue("5000") BigDecimal declineAbove) {
    }

    /**
     * Razorpay test mode. The secret and the webhook secret never leave the server.
     *
     * @param webhookSecret    verifies Razorpay's webhook calls; blank turns the webhook off
     * @param allowLive        live keys (rzp_live_...) are refused unless this is true
     * @param checkInterval    an unpaid order is checked with Razorpay this soon, then ever less often
     * @param maxCheckInterval ... up to this
     * @param giveUpAfter      an order nobody paid for is no longer checked after this
     */
    public record Razorpay(
            @DefaultValue("https://api.razorpay.com/v1") String baseUrl,
            String keyId,
            String keySecret,
            String webhookSecret,
            @DefaultValue("false") boolean allowLive,
            @DefaultValue("Rydex") String businessName,
            @DefaultValue("3s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout,
            @DefaultValue("30s") Duration checkInterval,
            @DefaultValue("30m") Duration maxCheckInterval,
            @DefaultValue("7d") Duration giveUpAfter) {

        public boolean webhookEnabled() {
            return webhookSecret != null && !webhookSecret.isBlank();
        }

        /** Fails startup on missing or live keys: a misconfigured provider must not take bookings. */
        public void validate() {
            if (keyId == null || keyId.isBlank() || keySecret == null || keySecret.isBlank()) {
                throw new IllegalStateException("PAYMENT_PROVIDER=razorpay needs RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET "
                        + "(test keys from the Razorpay dashboard; see services/payment-service/.env.example)");
            }
            if (!keyId.startsWith("rzp_test_") && !allowLive) {
                throw new IllegalStateException("RAZORPAY_KEY_ID is not a test key (rzp_test_...): Rydex only "
                        + "takes test-mode payments");
            }
        }
    }
}
