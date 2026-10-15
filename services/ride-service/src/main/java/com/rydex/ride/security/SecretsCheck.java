package com.rydex.ride.security;

import com.rydex.ride.crypto.CryptoProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to start with development secrets when {@code rydex.security.strict-secrets} is on
 * (RYDEX_STRICT_SECRETS=true, set on every deployment): the published OTP secret, the published
 * personal-data keys, or no JWT key file (an ephemeral key would sign out everyone on restart).
 * Locally it only warns.
 */
@Component
class SecretsCheck implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SecretsCheck.class);
    static final String DEV_MARKER = "dev-only";

    private final boolean strict;
    private final String otpSecret;
    private final CryptoProperties crypto;
    private final JwtProperties jwt;

    SecretsCheck(@Value("${rydex.security.strict-secrets:false}") boolean strict,
                 @Value("${rydex.otp.secret}") String otpSecret, CryptoProperties crypto, JwtProperties jwt) {
        this.strict = strict;
        this.otpSecret = otpSecret;
        this.crypto = crypto;
        this.jwt = jwt;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = problems();
        if (problems.isEmpty()) {
            return;
        }
        if (strict) {
            throw new IllegalStateException("Refusing to start with development secrets: " + String.join("; ", problems));
        }
        log.warn("Development secrets in use (fine locally, never in a deployment): {}", String.join("; ", problems));
    }

    List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (otpSecret == null || otpSecret.contains(DEV_MARKER) || otpSecret.length() < 32) {
            problems.add("OTP_SECRET is the development default or shorter than 32 characters");
        }
        if (isDevKey(crypto.dataKey())) {
            problems.add("PII_DATA_KEY is the development key");
        }
        if (isDevKey(crypto.indexKey())) {
            problems.add("PII_INDEX_KEY is the development key");
        }
        if (crypto.dataKey() != null && crypto.dataKey().equals(crypto.indexKey())) {
            problems.add("PII_DATA_KEY and PII_INDEX_KEY must differ");
        }
        if (jwt.privateKeyLocation() == null || !jwt.privateKeyLocation().exists()) {
            problems.add("no JWT signing key file (JWT_PRIVATE_KEY_LOCATION): tokens would use an ephemeral key");
        }
        return problems;
    }

    private static boolean isDevKey(String base64) {
        try {
            return base64 == null
                    || new String(Base64.getDecoder().decode(base64.trim()), StandardCharsets.ISO_8859_1).startsWith(DEV_MARKER);
        } catch (IllegalArgumentException e) {
            return false; // FieldCipher reports malformed keys
        }
    }
}
