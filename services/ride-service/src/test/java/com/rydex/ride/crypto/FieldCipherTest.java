package com.rydex.ride.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class FieldCipherTest {

    private static String key(char c) {
        return Base64.getEncoder().encodeToString(String.valueOf(c).repeat(32).getBytes());
    }

    private final FieldCipher cipher = new FieldCipher(new CryptoProperties(key('a'), key('b')));

    @Test
    void roundTripsWithAFreshIvEveryTime() {
        String a = cipher.encrypt("rider@example.com");
        String b = cipher.encrypt("rider@example.com");

        assertThat(a).startsWith("v1.").isNotEqualTo(b).doesNotContain("rider");
        assertThat(cipher.decrypt(a)).isEqualTo("rider@example.com");
        assertThat(cipher.decrypt(b)).isEqualTo("rider@example.com");
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }

    @Test
    void tamperedOrForeignValuesDoNotDecrypt() {
        String sealed = cipher.encrypt("+919876543210");
        byte[] raw = Base64.getDecoder().decode(sealed.substring(3));
        raw[raw.length - 1] ^= 1;
        String tampered = "v1." + Base64.getEncoder().encodeToString(raw);
        var otherKey = new FieldCipher(new CryptoProperties(key('c'), key('b')));

        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> otherKey.decrypt(sealed)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt("plain@example.com")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theEmailIndexIsStableCaseInsensitiveAndKeyed() {
        var otherKey = new FieldCipher(new CryptoProperties(key('a'), key('c')));

        assertThat(cipher.emailIndex(" Rider@Example.com ")).isEqualTo(cipher.emailIndex("rider@example.com")).hasSize(64);
        assertThat(otherKey.emailIndex("rider@example.com")).isNotEqualTo(cipher.emailIndex("rider@example.com"));
    }

    @Test
    void keysMustBe32Bytes() {
        assertThatThrownBy(() -> new FieldCipher(new CryptoProperties("c2hvcnQ=", key('b'))))
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new FieldCipher(new CryptoProperties(null, key('b'))))
                .hasMessageContaining("not set");
    }
}
