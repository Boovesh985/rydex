package com.rydex.ride.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Application-level encryption of personal data (ADR 0014).
 * <ul>
 *   <li>{@link #encrypt}: AES-256-GCM with a fresh 96-bit IV per value, stored as
 *       {@code v1.<base64(iv | ciphertext | tag)>}. The version prefix leaves room for key rotation.</li>
 *   <li>{@link #emailIndex}: a keyed HMAC of the normalised address, so an account can be found by
 *       email (sign-in, uniqueness) without storing the address in plain text.</li>
 * </ul>
 * A database dump alone therefore yields neither emails nor phone numbers.
 */
@Component
public class FieldCipher {

    static final String V1 = "v1.";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    /** Binds ciphertexts to Rydex's PII columns: a value copied from elsewhere does not decrypt. */
    private static final byte[] AAD = "rydex:pii:v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec dataKey;
    private final SecretKeySpec indexKey;

    public FieldCipher(CryptoProperties props) {
        this.dataKey = new SecretKeySpec(key("rydex.crypto.data-key", props.dataKey()), "AES");
        this.indexKey = new SecretKeySpec(key("rydex.crypto.index-key", props.indexKey()), "HmacSHA256");
    }

    /** Null stays null. */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, dataKey, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(AAD);
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return V1 + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length)
                    .put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt a field", e);
        }
    }

    /** Null stays null. Fails on a value that is not ours or was tampered with. */
    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (!stored.startsWith(V1)) {
            throw new IllegalStateException("Encrypted field has an unknown format");
        }
        try {
            byte[] raw = Base64.getDecoder().decode(stored.substring(V1.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, dataKey, new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not decrypt a field (wrong key or tampered value)", e);
        }
    }

    /** The lookup key for an email address: HMAC-SHA256 of the trimmed, lower-cased address, in hex. */
    public String emailIndex(String email) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(indexKey);
            return HexFormat.of().formatHex(mac.doFinal(normaliseEmail(email).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String normaliseEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static byte[] key(String name, String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(name + " is not valid base64", e);
        }
        if (key.length != 32) {
            throw new IllegalStateException(name + " must be 32 bytes (openssl rand -base64 32), got " + key.length);
        }
        return key;
    }
}
