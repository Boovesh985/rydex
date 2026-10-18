package com.rydex.common;

import java.util.List;

/**
 * Vehicle classes a rider can book and a driver can serve (ADR 0018): a bike taxi, an auto
 * rickshaw, and four kinds of cab. Declared cheapest first; estimates list them in this order.
 */
public enum RideType {
    /** a motorbike taxi: one pillion rider */
    BIKE,
    /** an auto rickshaw: three seats */
    AUTO,
    /** a hatchback cab */
    ECONOMY,
    /** a sedan cab */
    COMFORT,
    /** a six-seat MUV */
    XL,
    /** a premium sedan */
    PREMIUM;

    /**
     * The classes that may serve this ride at its own fare when none of its own class is free (a
     * free upgrade, ADR 0021): a hatchback ride may come as a sedan, a sedan ride as a premium sedan.
     * Never a smaller vehicle, never an XL (more seats is a different product), and not between
     * bikes, autos and cabs.
     */
    public List<RideType> upgrades() {
        return switch (this) {
            case ECONOMY -> List.of(COMFORT);
            case COMFORT -> List.of(PREMIUM);
            case BIKE, AUTO, XL, PREMIUM -> List.of();
        };
    }

    /** Whether a driver of {@code vehicle} may serve a ride of this type. */
    public boolean servedBy(RideType vehicle) {
        return vehicle == this || upgrades().contains(vehicle);
    }
}
