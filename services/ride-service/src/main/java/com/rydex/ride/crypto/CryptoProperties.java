package com.rydex.ride.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Keys for personal data at rest (ADR 0014), each 32 random bytes in base64
 * ({@code openssl rand -base64 32}).
 *
 * @param dataKey  AES-256-GCM key that encrypts email addresses and phone numbers
 * @param indexKey HMAC-SHA256 key for the email lookup index (a different key, so the index
 *                 reveals nothing about the ciphertext and vice versa)
 */
@ConfigurationProperties("rydex.crypto")
public record CryptoProperties(String dataKey, String indexKey) {
}
