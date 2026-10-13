package com.rydex.tracking.ride;

import com.rydex.common.ws.WsDestinations;
import com.rydex.common.ws.WsPush;
import com.rydex.events.EventEnvelope;
import com.rydex.events.RidePayload;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The notifications a ride event produces, for the rider and (where it concerns them) the driver. */
final class Notifications {

    private Notifications() {
    }

    static List<WsPush> forEvent(EventEnvelope<RidePayload> event, TrackedRide ride) {
        String reason = event.payload() == null ? null : event.payload().reason();
        List<WsPush> pushes = new ArrayList<>(2);
        switch (event.eventType()) {
            case RIDE_REQUESTED -> pushes.add(toRider(event, ride, "Finding your driver",
                    "We're looking for the nearest available driver."));
            case DRIVER_ASSIGNED -> {
                pushes.add(toRider(event, ride, "Driver on the way", "Your driver accepted and is heading to the pickup."));
                pushes.add(toDriver(event, ride, "Ride assigned", "Head to the pickup point."));
            }
            case NO_DRIVER_FOUND -> pushes.add(toRider(event, ride, "No drivers available",
                    reason != null ? reason + ". Please try again in a few minutes." : "Please try again in a few minutes."));
            case DRIVER_ARRIVED -> pushes.add(toRider(event, ride, "Your driver has arrived",
                    "Share your ride PIN with the driver to start the trip."));
            case RIDE_STARTED -> pushes.add(toRider(event, ride, "Trip started", "Enjoy your ride."));
            case RIDE_COMPLETED -> {
                String fare = ride.getFare() == null ? "" : " Fare: Rs " + ride.getFare().toPlainString() + ".";
                pushes.add(toRider(event, ride, "Trip completed", "Thanks for riding with Rydex." + fare));
                pushes.add(toDriver(event, ride, "Trip completed", "You're available for new rides." + fare));
            }
            case RIDE_CANCELLED -> {
                String why = reason != null ? reason : "No reason given.";
                BigDecimal fee = event.payload() == null ? null : event.payload().cancellationFee();
                String charged = fee == null ? "" : (why.endsWith(".") ? " " : ". ") + "A cancellation fee of Rs "
                        + fee.stripTrailingZeros().toPlainString() + " applies: the driver had arrived.";
                pushes.add(toRider(event, ride, "Ride cancelled", why + charged));
                pushes.add(toDriver(event, ride, "Ride cancelled", why));
            }
            default -> {
                // not a ride step
            }
        }
        pushes.removeIf(p -> p == null);
        return pushes;
    }

    private static WsPush toRider(EventEnvelope<RidePayload> event, TrackedRide ride, String title, String message) {
        return push(ride.getRiderId(), event, ride, title, message);
    }

    /** Null (dropped) while no driver is assigned. */
    private static WsPush toDriver(EventEnvelope<RidePayload> event, TrackedRide ride, String title, String message) {
        return ride.getDriverId() == null ? null : push(ride.getDriverId(), event, ride, title, message);
    }

    private static WsPush push(UUID user, EventEnvelope<RidePayload> event, TrackedRide ride, String title, String message) {
        return WsPush.toUser(user, WsDestinations.NOTIFICATIONS, new RideNotification(event.eventId(),
                event.eventType().name(), ride.getId(), ride.getStatus(), title, message, ride.getDriverId(),
                ride.getFare(), event.occurredAt()));
    }
}
