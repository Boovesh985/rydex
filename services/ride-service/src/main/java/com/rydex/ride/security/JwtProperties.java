package com.rydex.ride.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * @param privateKeyLocation PKCS#8 PEM. If unset, an ephemeral key pair is generated
 *                           (tokens then stop validating after a restart; dev only).
 * @param publicKeyLocation  X.509 PEM matching the private key.
 */
@ConfigurationProperties("rydex.jwt")
public record JwtProperties(
        String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Resource privateKeyLocation,
        Resource publicKeyLocation) {
}
