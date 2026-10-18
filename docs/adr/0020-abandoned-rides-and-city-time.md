# 20. Abandoned rides, honest figures and the city's clock

Date: 2026-10-18 · Status: Accepted (extends ADR 0010 and ADR 0016)

## Context
After the laptop was off for a night, 23 rides were stuck IN_PROGRESS, some for three days, and 8 more were DRIVER_ASSIGNED with drivers long gone. Five of them were the owner's own test rides.
- Only the driver moves a ride on after assignment, and IN_PROGRESS could only become COMPLETED. A ride whose driver vanished (the app closed for good, the simulator stopped mid-trip) stayed under way forever.
- Its rider could not book again, and it counted as in flight on the admin map.
- The admin "Pickup wait" was a mean, so these leftovers made it read 1 h 14 min.

Two smaller things showed up at the same time:
- Day and night followed the viewer's computer clock, not Chennai's.
- The simulator reported fast-forwarded speeds: at `--speedup 5` a bike at 30 km/h showed 150 km/h.

## Decision
- **Abandoned-ride sweeper (ride-service):** every 30 s it ends a ride under way when both are true:
  - the ride has not moved on for `rydex.rides.abandoned-after` (15 min);
  - its driver has sent no heartbeat for as long.

  The system cancels it, free of charge, with the reason "The driver stopped responding, so the ride was ended. You were not charged." The usual RIDE_CANCELLED event frees the driver in driver-service and tells the rider and the driver (tracking-service now accepts a cancellation of a trip under way).
- **IN_PROGRESS → CANCELLED** is allowed in the state machine, but only for the system: `RideService.cancel` still refuses riders, drivers and admins with 409.
- **When a driver was last heard from:** `driver:lastseen` cannot answer this, because the presence reaper removes a driver from it after 30 s of silence. The heartbeat script now also writes `driver:heard` (a ZSET the reaper never clears). ride-service reads it through `libs/presence` (`PresenceStore.lastHeard`).
  - It falls back to `driver:lastseen` while the writers are still the old version, so a rollout cannot end live trips.
- **Medians on the admin panel:** assign time and pickup wait are medians (`percentile_cont(0.5)`), so one ride left hanging cannot swamp a day of normal pickups. The JSON field names stay `avgAssignS`/`avgPickupS`.
- **The city's clock:** day (6 am to 6 pm) and night follow Chennai time (IST, UTC+5:30, no daylight saving) for every viewer (`cityHour`, `isNightNow`, `nextDayNightSwitch` in `map/theme.ts`).
- **Simulator speeds:** NPC drivers report the speed they drive at in the simulated city (`world_speed`), not the distance covered per real second.

## Consequences
- **A driver who loses signal for over 15 minutes mid-trip loses the ride,** and the rider is not charged for it. That suits a demo city; a production app would ask the driver first.
- **`driver:heard` grows** by one entry per driver ever online: about 50 here.
- **The stuck rides** in an existing database end within a minute of ride-service starting.
