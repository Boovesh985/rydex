package com.rydex.ride.ride;

import java.util.EnumSet;
import java.util.Set;

/**
 * Ride lifecycle:
 * <pre>
 * REQUESTED -> DRIVER_ASSIGNED -> DRIVER_ARRIVED -> IN_PROGRESS -> COMPLETED
 * REQUESTED | DRIVER_ASSIGNED | DRIVER_ARRIVED -> CANCELLED
 * IN_PROGRESS -> CANCELLED (only the system, for a trip whose driver stopped responding)
 * REQUESTED -> NO_DRIVER_FOUND
 * </pre>
 */
public enum RideStatus {
    REQUESTED,
    DRIVER_ASSIGNED,
    DRIVER_ARRIVED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    NO_DRIVER_FOUND;

    public Set<RideStatus> allowedNext() {
        return switch (this) {
            case REQUESTED -> EnumSet.of(DRIVER_ASSIGNED, CANCELLED, NO_DRIVER_FOUND);
            case DRIVER_ASSIGNED -> EnumSet.of(DRIVER_ARRIVED, CANCELLED);
            case DRIVER_ARRIVED -> EnumSet.of(IN_PROGRESS, CANCELLED);
            case IN_PROGRESS -> EnumSet.of(COMPLETED, CANCELLED);
            case COMPLETED, CANCELLED, NO_DRIVER_FOUND -> EnumSet.noneOf(RideStatus.class);
        };
    }

    public boolean canTransitionTo(RideStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }
}
