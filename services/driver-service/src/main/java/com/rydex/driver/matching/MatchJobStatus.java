package com.rydex.driver.matching;

public enum MatchJobStatus {
    /** Looking for the next driver to offer the ride to. */
    SEARCHING,
    /** Waiting for the current driver to accept or reject. */
    OFFERED,
    ASSIGNED,
    NO_DRIVER_FOUND,
    CANCELLED;

    public boolean isActive() {
        return this == SEARCHING || this == OFFERED;
    }
}
