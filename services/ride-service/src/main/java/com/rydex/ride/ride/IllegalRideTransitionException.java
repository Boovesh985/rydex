package com.rydex.ride.ride;

public class IllegalRideTransitionException extends RuntimeException {

    private final RideStatus from;
    private final RideStatus to;

    public IllegalRideTransitionException(RideStatus from, RideStatus to) {
        super("Cannot move a ride from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public RideStatus from() {
        return from;
    }

    public RideStatus to() {
        return to;
    }
}
