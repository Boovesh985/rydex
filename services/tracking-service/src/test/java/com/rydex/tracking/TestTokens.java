package com.rydex.tracking;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Stands in for ride-service's token issuing in tests: signs access tokens with a throwaway RSA key and
 * verifies them with the matching public key instead of fetching the JWKS over HTTP.
 */
public final class TestTokens {

    public static final String ISSUER = "rydex";

    private static final KeyPair KEYS = rsa();
    private static final JwtEncoder ENCODER = encoder(KEYS);

    private TestTokens() {
    }

    public static String driver(UUID id) {
        return issue(id, "DRIVER", "Test Driver", ISSUER, ENCODER);
    }

    public static String rider(UUID id) {
        return issue(id, "RIDER", "Test Rider", ISSUER, ENCODER);
    }

    public static String admin() {
        return issue(UUID.randomUUID(), "ADMIN", "Test Admin", ISSUER, ENCODER);
    }

    /** A valid token that expires after {@code ttl}. */
    public static String expiringIn(UUID id, String role, Duration ttl) {
        return issue(id, role, "Test User", ISSUER, ENCODER, ttl);
    }

    /** Correct key, wrong issuer: must be rejected. */
    public static String withIssuer(UUID id, String issuer) {
        return issue(id, "DRIVER", "Test Driver", issuer, ENCODER);
    }

    /** Valid claims signed with a key the service does not trust: must be rejected. */
    public static String signedByStranger(UUID id) {
        return issue(id, "DRIVER", "Test Driver", ISSUER, encoder(rsa()));
    }

    private static String issue(UUID id, String role, String name, String issuer, JwtEncoder encoder) {
        return issue(id, role, name, issuer, encoder, Duration.ofMinutes(15));
    }

    private static String issue(UUID id, String role, String name, String issuer, JwtEncoder encoder, Duration ttl) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(id.toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("role", role)
                .claim("name", name)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("test").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static JwtEncoder encoder(KeyPair keys) {
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) keys.getPublic())
                .privateKey((RSAPrivateKey) keys.getPrivate())
                .keyID("test")
                .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return decoder;
        }
    }
}
