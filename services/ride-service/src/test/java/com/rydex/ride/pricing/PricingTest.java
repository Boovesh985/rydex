package com.rydex.ride.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.rydex.common.RideType;
import com.rydex.ride.pricing.FareProperties.Tariff;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PricingTest {

    private final FareProperties props = new FareProperties(Map.of(
            RideType.ECONOMY, new Tariff(bd("40"), bd("12"), bd("1.5"), bd("60")),
            RideType.COMFORT, new Tariff(bd("60"), bd("16"), bd("2.0"), bd("90")),
            RideType.XL, new Tariff(bd("90"), bd("22"), bd("2.5"), bd("140"))),
            bd("2.5"));

    private final FareCalculator fares = new FareCalculator(props);
    private final SurgeCalculator surge = new SurgeCalculator(props);

    @ParameterizedTest(name = "{0} {1}m {2}s x{3} = {4}")
    @CsvSource({
            // base + perKm*km + perMin*min, times surge, rounded half-up to whole rupees
            "ECONOMY,  5000,  900, 1.0, 123", // 40 + 60 + 22.5 = 122.5
            "COMFORT,  5000,  900, 1.0, 170", // 60 + 80 + 30
            "XL,       5000,  900, 1.0, 238", // 90 + 110 + 37.5 = 237.5
            "ECONOMY,  5000,  900, 1.5, 184", // 122.5 * 1.5 = 183.75
            "ECONOMY,   500,  120, 1.0, 60",  // 49 -> minimum fare
            "XL,      12345, 1800, 2.0, 873"  // (90 + 271.59 + 75) * 2 = 873.18
    })
    void fareFormula(RideType type, int meters, int seconds, String surgeX, String expected) {
        BigDecimal actual = fares.fare(type, meters, seconds, bd(surgeX));

        assertThat(actual).isEqualByComparingTo(expected);
        assertThat(actual.scale()).isEqualTo(2);
    }

    @ParameterizedTest(name = "demand {0} / supply {1} -> x{2}")
    @CsvSource({
            "0,   0, 1.0",
            "10,  0, 1.0",  // no supply signal -> no surge
            "5,  10, 1.0",
            "10, 10, 1.0",
            "20, 10, 1.3",  // 1 + 0.25 * 1 = 1.25 -> 1.3
            "30, 10, 1.5",
            "60, 10, 2.3",  // 1 + 0.25 * 5 = 2.25 -> 2.3
            "100, 5, 2.5"   // capped
    })
    void surgeFromDemandSupply(long demand, long supply, String expected) {
        assertThat(surge.surge(demand, supply)).isEqualByComparingTo(expected);
    }

    @Test
    void surgeNeverBelowOneOrAboveMax() {
        for (long d = 0; d < 200; d += 7) {
            for (long s = 0; s < 50; s += 3) {
                assertThat(surge.surge(d, s)).isBetween(BigDecimal.ONE, bd("2.5"));
            }
        }
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
