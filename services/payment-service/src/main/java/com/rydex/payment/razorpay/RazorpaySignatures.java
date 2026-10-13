package com.rydex.payment.razorpay;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Razorpay's HMAC-SHA256 signatures. Checkout returns {@code hex(hmac(order_id + "|" + payment_id,
 * key_secret))}; webhooks carry {@code hex(hmac(raw body, webhook_secret))} in X-Razorpay-Signature.
 */
public final class RazorpaySignatures {

    private RazorpaySignatures() {
    }

    public static boolean checkoutValid(String orderId, String paymentId, String signature, String keySecret) {
        return matches(hmacHex(orderId + "|" + paymentId, keySecret), signature);
    }

    public static boolean webhookValid(String rawBody, String signature, String webhookSecret) {
        return matches(hmacHex(rawBody, webhookSecret), signature);
    }

    public static String hmacHex(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time, so the comparison leaks nothing about the expected value. */
    private static boolean matches(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }
}
