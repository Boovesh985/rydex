package com.rydex.tracking.ride;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What riders and drivers receive on {@code /user/queue/notifications} when a ride changes.
 *
 * @param eventId  the ride event behind it; clients can use it to drop a repeated notification
 * @param type     the event type, e.g. {@code DRIVER_ARRIVED}
 * @param status   the ride's status after the event
 * @param title    short text for a toast
 * @param message  one sentence of detail
 * @param driverId set once a driver is assigned
 * @param fare     INR; the estimate, or the final fare once completed
 */
public record RideNotification(UUID eventId, String type, UUID rideId, TrackedRide.Status status, String title,
                               String message, UUID driverId, BigDecimal fare, Instant at) {
}
