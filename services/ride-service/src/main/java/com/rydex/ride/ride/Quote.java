package com.rydex.ride.ride;

import java.math.BigDecimal;

/** Price of one ride type for one route, as computed by the server. */
public record Quote(BigDecimal fare, int distanceM, int durationS, BigDecimal surge) {
}
