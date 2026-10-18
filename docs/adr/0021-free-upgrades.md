# 21. Free upgrades

Date: 2026-10-18 · Status: Accepted (extends ADR 0008 and ADR 0018)

## Context
- Matching only ever offered a ride to drivers of the booked class.
- With six classes, the rare ones ran out first: the city has 3 Premium and 4 XL drivers.
- Even an Economy ride failed whenever no hatchback was free nearby, while sedans cruised past the pickup empty.
- Ride-hailing apps answer this with a free upgrade.

## Decision
- **Which classes:** `RideType.upgrades()` lists the classes that may serve a ride at its own fare:
  - Economy may come as a Comfort sedan;
  - Comfort may come as a Premium sedan.

  Never a smaller vehicle, never an XL (more seats is a different product, and an XL is dearer to run), and never between bikes, autos and cabs. `servedBy(vehicle)` is the one check.
- **Matching (driver-service):** the booked class is searched first, at every radius (3, 5, 8 km). Only if nobody of it is free is the same search run for each upgrade class. A free driver of the booked class 8 km away still beats a sedan at the door. The ride keeps its type and its fare; offers, assignment and the offer's own checks accept an upgrade driver.
- **Estimate (ride-service):** a class with nobody of its own nearby, but an upgrade driver nearby, reports that driver's ETA and `upgradeTo`, so the booking screen does not say "No cars nearby" for a ride that would be served.
- **Web app:**
  - the booking card says "May come as a Comfort";
  - the rider's driver panel says "Free upgrade: a Comfort at the Economy fare";
  - the offer tells an upgrade driver "you are the upgrade, at its fare".

## Consequences
- **Upgrade drivers earn the booked fare.** Fine for a demo; a real service would top them up.
- **Fewer "no driver found"** for Economy and Comfort.
- **XL, Premium, bikes and autos match as before.**
