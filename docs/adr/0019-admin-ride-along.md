# 19. Riding along from the operations map

Date: 2026-10-17 · Status: Accepted (extends ADR 0016, ADR 0017 and ADR 0018)

## Context
The user asked for the admin's 3D view to work like this:
- clicking a moving vehicle fixes the camera on it, as a chase camera;
- its trip details appear at the side;
- closing (X) goes back to the normal 3D view.

## Decision
- **Picking:**
  - In realistic mode the fleet is drawn by three.js, so there is no element to click. `Fleet` (`onSelect`) projects each vehicle's shown position to the screen and picks the nearest one under the pointer. The hit area is the larger of 34 px and the vehicle's real size, lifted for the body's height in a tilted view.
  - The pointer turns into a hand over a vehicle.
  - In 2D the marker itself is clickable.
- **The followed vehicle:** while picked, it leaves the fleet (`hide`) and is drawn as an `AnimatedCar`, the component the trip pages use. That gives the admin the same smooth glide paced to the fixes, the same chase camera (`follow`, closer for bikes and autos), the pulsing ring, the night headlight beams and the pillion or passengers on a trip. The fleet's 3-second glides would have made the chase camera jerky.
- **Back to the view you had:** `RememberView` keeps the camera from the moment the ride-along starts and eases back to it when the ride-along ends. Switching from one vehicle to another keeps the original view.
- **Side panel (`pages/admin/RideAlong.tsx`):** it replaces the operations panel while riding along. It shows:
  - what the vehicle is doing and its speed;
  - the driver (name, rating, make and model, type, plate, vehicle icon);
  - for a ride, the live ETA meter (pushed over STOMP, polled as a fallback), trip progress, pickup and drop, fare, whether the rider is an NPC, status and payment, the timeline, and a link to the trip page.
- **Controls:**
  - The panel's X button or Esc ends the ride-along.
  - Leaving realistic mode keeps the panel.
  - A driver who goes offline ends it.
- **Map extras:** a speedometer while riding along, and a "Click any vehicle to ride along" hint while not.
- **The ETA meter** moved to `pages/tripMeter.ts`, so the trip page and the panel say the same thing.
- **driver-service** has a new endpoint, `GET /api/admin/drivers/{driverId}` (ADMIN only). The live list carries no names or vehicles, and a free car has no ride to look its driver up by.

## Consequences
- **Another admin endpoint** in driver-service, read-only. It answers 404 for an unknown driver and 403 for anyone but an admin.
- **Picking is screen-space,** not a three.js raycast. Two vehicles overlapping on screen pick the nearer projected centre. That is enough for a city view, and costs about 40 projections per click.
- **Trip progress in the panel** is the remaining road distance against the planned trip. It is a glance, not the trip page's forward-only route matching.
