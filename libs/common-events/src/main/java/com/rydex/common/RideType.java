package com.rydex.common;

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
    PREMIUM
}
