# 10. Recovering rides and drivers when an event is lost

Date: 2026-10-13 · Status: Accepted

## Context
ADR 0009 retries a failing event for about 5 minutes, then parks it in the dead-letter topic, which nothing replays automatically. An audit after Phase 3 traced what each lost event would leave behind:

| Lost event | Effect without a safety net |
|---|---|
| RIDE_REQUESTED (to driver-service) | The ride stays REQUESTED forever. The rider can't book again (one active ride per rider). |
| DRIVER_ASSIGNED / NO_DRIVER_FOUND (to ride-service) | The same, and with DRIVER_ASSIGNED the driver also sits ON_TRIP for a ride that never started. |
| RIDE_COMPLETED / RIDE_CANCELLED (to driver-service) | The driver stays ON_TRIP. They can't go offline (blocked mid-trip) and get no offers. |

## Decision
- **Unmatched-ride sweeper (ride-service).**
  - Every 30 s it looks for rides still REQUESTED 5 minutes after booking (`rydex.rides.unmatched-timeout`), using a partial index on `requested_at`.
  - Matching gives up after about 2 minutes, so by then the outcome is lost, not late.
  - It cancels each one as `SYSTEM` with the reason "No driver found in time", and publishes RIDE_CANCELLED like any cancellation.
  - That event makes driver-service stop matching and free a driver it may have assigned. If a late DRIVER_ASSIGNED reaches ride-service afterwards, it is ignored (the ride is no longer REQUESTED), and the RIDE_CANCELLED has already freed the driver. Both orders converge, as in ADR 0009.
  - Each ride is cancelled in its own transaction with the usual version check, so a racing outcome or rider action simply wins.
- **Admin release (driver-service).**
  - `POST /api/admin/drivers/{id}/release` takes a driver out of ON_TRIP and sets them OFFLINE, so they choose when to go online again.
  - It is for rides that are already over in ride-service. If the ride is still live there, the admin cancels it (`POST /api/rides/{id}/cancel`, allowed for admins) and RIDE_CANCELLED frees the driver normally.
- **Matching deadline from booking time.** driver-service measures the 90 s search from the event's `occurredAt` (when the rider booked), not from when it read the event. A request that waited in Kafka gets at least one short search (the offer timeout) instead of a fresh 90 s.

## Consequences
- No ride waits more than about 5.5 minutes for a matching outcome, whatever fails in between.
- A driver stuck by a lost completion needs an admin. Phase 9 should alert on DLT traffic and on drivers ON_TRIP for longer than a few hours.
- Automatic DLT replay is still out of scope.
