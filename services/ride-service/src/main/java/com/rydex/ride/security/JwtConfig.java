package com.rydex.ride.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * ride-service is the token issuer. It signs with RS256 and publishes the public key as
 * a JWKS document so every other service can verify tokens without sharing a secret.
 */
@Configuration(proxyBeanMethods = false)
class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

    @Bean
    RSAKey signingKey(JwtProperties props) throws IOException, NoSuchAlgorithmException {
        RSAPublicKey publicKey;
        RSAPrivateKey privateKey;
        if (props.privateKeyLocation() != null && props.privateKeyLocation().exists()) {
            try (InputStream priv = props.privateKeyLocation().getInputStream();
                 InputStream pub = props.publicKeyLocation().getInputStream()) {
                privateKey = RsaKeyConverters.pkcs8().convert(priv);
                publicKey = RsaKeyConverters.x509().convert(pub);
            }
            log.info("Loaded JWT signing key from {}", props.privateKeyLocation());
        } else {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            privateKey = (RSAPrivateKey) pair.getPrivate();
            publicKey = (RSAPublicKey) pair.getPublic();
            log.warn("No JWT key configured (rydex.jwt.private-key-location); using an EPHEMERAL key. "
                    + "Tokens will be invalid after restart. Run infra/scripts/gen-jwt-keys.sh for a stable key.");
        }
        try {
            return new RSAKey.Builder(publicKey).privateKey(privateKey).keyIDFromThumbprint().build();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("Could not build RSA JWK", e);
        }
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey signingKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
    }

    @Bean
    JwtDecoder jwtDecoder(RSAKey signingKey, JwtProperties props) throws com.nimbusds.jose.JOSEException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.issuer()));
        return decoder;
    }
}
