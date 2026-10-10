package com.rydex.ride.ride;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OtpServiceTest {

    private final OtpService otps = new OtpService("test-secret");

    @Test
    void otpIsFourDigitsAndStablePerRide() {
        UUID ride = UUID.randomUUID();

        assertThat(otps.otpFor(ride)).matches("\\d{4}").isEqualTo(otps.otpFor(ride));
    }

    @Test
    void otpsDifferAcrossRidesAndSecrets() {
        var seen = new HashSet<String>();
        for (int i = 0; i < 200; i++) {
            seen.add(otps.otpFor(UUID.randomUUID()));
        }
        UUID ride = UUID.fromString("00000000-0000-4000-8000-000000000123");

        assertThat(seen).hasSizeGreaterThan(150); // well spread over 10,000 values
        assertThat(new OtpService("other-secret").otpFor(ride)).isNotEqualTo(otps.otpFor(ride));
    }

    @Test
    void verifiesAgainstStoredHashOnly() {
        UUID ride = UUID.randomUUID();
        String otp = otps.otpFor(ride);
        String stored = otps.hash(ride, otp);

        assertThat(stored).hasSize(64).doesNotContain(otp);
        assertThat(otps.matches(ride, otp, stored)).isTrue();
        assertThat(otps.matches(ride, otp.equals("0000") ? "1111" : "0000", stored)).isFalse();
        assertThat(otps.matches(UUID.randomUUID(), otp, stored)).as("hash is bound to the ride").isFalse();
    }
}
