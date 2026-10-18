package com.rydex.driver.driver;

import static org.assertj.core.api.Assertions.assertThat;

import com.rydex.common.RideType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DriverRatingTest {

    private static final Instant T0 = Instant.parse("2026-10-18T08:00:00Z");

    @Test
    void aNewDriversFiveStarsCountAsFiveTripsThenItIsThePlainAverage() {
        Driver d = Driver.register(UUID.randomUUID(), "Driver 51", RideType.BIKE, "TN 01 AB 1234", "Honda Shine", T0);

        d.rate(1, T0); // one bad first trip: (5 x 5 + 1) / 6
        assertThat(d.getRating()).isEqualByComparingTo("4.33");
        for (int i = 0; i < 4; i++) {
            d.rate(5, T0);
        }
        assertThat(d.getRatingCount()).isEqualTo(5);
        // from the fifth rating on, each new one weighs 1 / (count + 1)
        d.rate(3, T0);
        assertThat(d.getRatingCount()).isEqualTo(6);
        assertThat(d.getRating().doubleValue()).isBetween(4.0, 4.6);
    }
}
