package com.rydex.ride.pricing;

import com.rydex.common.RideType;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * {@code fare = max(minimum, (base + perKm * km + perMin * min) * surge)}, rounded to whole rupees.
 */
@Component
public class FareCalculator {

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);

    private final FareProperties props;

    public FareCalculator(FareProperties props) {
        this.props = props;
    }

    public BigDecimal fare(RideType type, int distanceM, int durationS, BigDecimal surge) {
        var t = props.tariff(type);
        BigDecimal km = BigDecimal.valueOf(distanceM).divide(THOUSAND, MathContext.DECIMAL64);
        BigDecimal min = BigDecimal.valueOf(durationS).divide(SIXTY, MathContext.DECIMAL64);
        BigDecimal raw = t.base().add(t.perKm().multiply(km)).add(t.perMin().multiply(min)).multiply(surge);
        return raw.max(t.minimum()).setScale(0, RoundingMode.HALF_UP).setScale(2);
    }
}
