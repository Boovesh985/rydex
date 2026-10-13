package com.rydex.common;

/**
 * Driver availability, shared by driver-service (source of truth) and the Redis presence projection. OFFLINE and AVAILABLE are chosen by the driver; ON_TRIP is set by
 * matching when an offer is accepted and cleared when the ride completes or is cancelled.
 */
public enum DriverStatus {
    OFFLINE,
    AVAILABLE,
    ON_TRIP
}
