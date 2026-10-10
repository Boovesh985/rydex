package com.rydex.ride.pricing;

import com.rydex.common.RideType;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tariff per ride type, in INR. */
@ConfigurationProperties("rydex.fares")
public record FareProperties(Map<RideType, Tariff> tariffs, BigDecimal maxSurge) {

    public record Tariff(BigDecimal base, BigDecimal perKm, BigDecimal perMin, BigDecimal minimum) {
    }

    public Tariff tariff(RideType type) {
        Tariff t = tariffs.get(type);
        if (t == null) {
            throw new IllegalStateException("No tariff configured for " + type);
        }
        return t;
    }
}
