package com.rydex.ride.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * Surge from the demand/supply ratio around the pickup:
 * {@code clamp(1.0, max, 1 + 0.25 * (demand / supply - 1))}, in steps of 0.1.
 * With no drivers around there is no supply signal, so no surge is applied.
 */
@Component
public class SurgeCalculator {

    private static final BigDecimal SLOPE = new BigDecimal("0.25");

    private final BigDecimal maxSurge;

    public SurgeCalculator(FareProperties props) {
        this.maxSurge = props.maxSurge();
    }

    public BigDecimal surge(long demand, long supply) {
        if (supply <= 0 || demand <= supply) {
            return BigDecimal.ONE.setScale(1);
        }
        BigDecimal ratio = BigDecimal.valueOf(demand).divide(BigDecimal.valueOf(supply), 4, RoundingMode.HALF_UP);
        BigDecimal surge = BigDecimal.ONE.add(SLOPE.multiply(ratio.subtract(BigDecimal.ONE)));
        return surge.min(maxSurge).setScale(1, RoundingMode.HALF_UP);
    }
}
