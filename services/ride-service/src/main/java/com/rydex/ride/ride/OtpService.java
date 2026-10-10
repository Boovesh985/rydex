package com.rydex.ride.ride;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 4-digit ride OTP. It is derived from the ride id with an HMAC, so the rider can be
 * shown it at any time without it ever being stored in plain text. The ride row keeps
 * only a SHA-256 of it, which the driver's input is checked against.
 */
@Service
public class OtpService {

    private final SecretKeySpec key;

    public OtpService(@Value("${rydex.otp.secret}") String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String otpFor(UUID rideId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] h = mac.doFinal(rideId.toString().getBytes(StandardCharsets.UTF_8));
            int value = ((h[0] & 0x7f) << 24 | (h[1] & 0xff) << 16 | (h[2] & 0xff) << 8 | (h[3] & 0xff)) % 10_000;
            return String.format("%04d", value);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    public String hash(UUID rideId, String otp) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest((rideId + ":" + otp).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean matches(UUID rideId, String candidate, String storedHash) {
        return MessageDigest.isEqual(
                hash(rideId, candidate).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
