package com.rydex.ride.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rydex.ride.crypto.CryptoProperties;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

class SecretsCheckTest {

    private static final String DEV_DATA = "ZGV2LW9ubHktcGlpLWRhdGEta2V5LTMyLWJ5dGVzISE=";
    private static final String DEV_INDEX = "ZGV2LW9ubHktcGlpLWluZGV4LWtleS0zMmJ5dGVzISE=";
    private static final String STRONG_A = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final String STRONG_B = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes());

    private static JwtProperties jwt(Resource key) {
        return new JwtProperties("rydex", Duration.ofMinutes(15), Duration.ofDays(14), key, key);
    }

    private static final Resource KEY_FILE = new ByteArrayResource(new byte[] {1});

    @Test
    void strictModeRefusesTheDevelopmentSecrets() {
        var check = new SecretsCheck(true, "dev-only-otp-secret-change-me-in-prod",
                new CryptoProperties(DEV_DATA, DEV_INDEX), jwt(null));

        assertThat(check.problems()).hasSize(4);
        assertThatThrownBy(check::afterPropertiesSet).hasMessageContaining("Refusing to start")
                .hasMessageContaining("OTP_SECRET").hasMessageContaining("PII_DATA_KEY").hasMessageContaining("JWT");
    }

    @Test
    void strictModeAcceptsRealSecrets() {
        var check = new SecretsCheck(true, "a-real-otp-secret-with-at-least-32-chars",
                new CryptoProperties(STRONG_A, STRONG_B), jwt(KEY_FILE));

        assertThat(check.problems()).isEmpty();
        assertThatCode(check::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void theTwoKeysMustDiffer() {
        var check = new SecretsCheck(true, "a-real-otp-secret-with-at-least-32-chars",
                new CryptoProperties(STRONG_A, STRONG_A), jwt(KEY_FILE));

        assertThat(check.problems()).containsExactly("PII_DATA_KEY and PII_INDEX_KEY must differ");
    }

    @Test
    void developmentOnlyWarns() {
        var check = new SecretsCheck(false, "dev-only-otp-secret-change-me-in-prod",
                new CryptoProperties(DEV_DATA, DEV_INDEX), jwt(null));

        assertThatCode(check::afterPropertiesSet).doesNotThrowAnyException();
    }
}
