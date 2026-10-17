# 18. Bikes, autos and cabs in one app

Date: 2026-10-17 · Status: Accepted (extends ADR 0008, ADR 0016 and ADR 0017)

## Context
The user asked for an all-in-one app, as people in Indian cities use them:
- a bike taxi;
- an auto rickshaw;
- several kinds of cab;
- each with its own 3D model in realistic mode.

Until now Rydex had three cab types (Economy, Comfort, XL). A ride type runs through every layer:
- the `RideType` enum shared by all services;
- check constraints on `rides.ride_type` and `drivers.vehicle_type`;
- tariffs and cancellation fees;
- the driver seed and the simulator's fleet;
- the event contract;
- the web app's labels, pickers, map markers and 3D models.

## Decision

### Six ride types, cheapest first
`RideType` is now `BIKE, AUTO, ECONOMY, COMFORT, XL, PREMIUM`. Estimates list them in this order.

| Type | Vehicle | Seats | Base | /km | /min | Minimum | Late-cancel fee |
|---|---|---|---|---|---|---|---|
| BIKE | motorbike taxi | 1 | ₹20 | ₹6 | ₹0.5 | ₹30 | ₹20 |
| AUTO | auto rickshaw | 3 | ₹30 | ₹9 | ₹1.0 | ₹40 | ₹30 |
| ECONOMY | hatchback | 4 | ₹40 | ₹12 | ₹1.5 | ₹60 | ₹50 |
| COMFORT | sedan | 4 | ₹60 | ₹16 | ₹2.0 | ₹90 | ₹75 |
| XL | MUV | 6 | ₹90 | ₹22 | ₹2.5 | ₹140 | ₹100 |
| PREMIUM | premium sedan | 4 | ₹120 | ₹28 | ₹3.0 | ₹200 | ₹125 |

- **Matching:** unchanged. Matching is already per type (one Redis GEO index per type), so a bike ride is only offered to bikes.
- **Product names:** riders see plain names (Bike, Auto, Economy, Comfort, XL, Premium), not any company's product names.

### Data and fleet
- **Migrations:** ride-service `V9` and driver-service `V6` widen the check constraints.
- **The seeded fleet** is 12 Economy, 7 Comfort, 4 XL, 3 Premium, 13 Bike and 11 Auto, with real makes (Hero Splendor Plus, Bajaj RE, Toyota Camry and others).
  - `driver01` stays an Economy cab.
  - The repeatable seed also re-types an existing database's NPC drivers (02-50) to this mix whenever it runs, skipping any driver on a trip. `driver01` is never touched.
- **Changing vehicle:** a driver can change vehicle from the dashboard while offline (**Change vehicle**: the onboarding form, prefilled). The server already allowed this. So the owner can drive `driver01` as a bike, an auto or any cab.

### Simulator
- **Ride choice:** NPC riders book 30% bikes, 25% autos, 22% Economy, 12% Comfort, 7% XL and 4% Premium.
- **Traffic:** bikes weave through jams. A jam that slows a car to a factor f slows a bike only to √f.
  - An auto drives at 80% of a car's pace.
  - This applies only to how the simulator drives. The traffic model shared with the web app is unchanged.

### Looks (`lib/vehicles.ts`, `components/VehicleIcon.tsx`, `map/car3d.ts`)
- **One palette** (`VEHICLE_PAINT`) is used by the 2D markers, the admin legend, the ride pickers and the 3D models:
  - a red motorbike;
  - a green auto with a yellow canopy (the CNG livery);
  - the yellow call taxi, the white sedan and the graphite MUV;
  - a midnight-blue premium sedan.
- **2D:** each kind has its own top-down marker: a bike with its rider's helmet, an auto's narrow nose and canopy, or a car. The ride pickers show a side-view icon per type.
- **3D bike:** built from code, like the cars.
  - It has a tank, side covers and a tail cowl, an engine with fins, the frame, swingarm and red shock springs, forks and mudguards, the handlebar and mirrors, a chrome exhaust and spoked wheels with a brake disc.
  - Its driver wears a yellow jacket and a helmet. A pillion rider in a white helmet sits behind while the ride is in progress.
- **3D auto:**
  - The driver's cowl sits over a single front wheel, with a round headlamp on top.
  - It has a raked windscreen, the passenger tub with wheel arches and open doorways, a rexine bench, and a curved canopy on poles with grab rails.
  - The driver wears a khaki uniform. Two passengers sit on the back bench during the trip.
- **People:** shared low-poly seated figures, placed by hip, knees, feet and hands.
- **Motion:**
  - A bike leans into a turn as far as the corner needs: tan(lean) = v·ω/g, up to 34°. Its wheels lean with it, around the tyres' contact with the road.
  - An auto rolls out of a turn more than a car does.
  - Only front wheels steer. This fixes cars, whose rear wheels used to steer.
- **Size:** fleet vehicles grow to stay readable when zoomed out against a car's length, so a bike stays smaller than a cab.
- **Chase camera:** it rides 0.8 zoom levels closer behind a bike and 0.4 behind an auto.
- **Fixes on the way:**
  - The wheel-arch liners line the top of the arch, not the back half.
  - Tyre beads no longer dip below the road.

## Consequences
- **No new services and no new tables.** It is one enum, two constraints, configuration and the web app.
- **The six options are a longer list** in the booking sheet. It is ordered cheapest first, and the default stays Economy.
- **A smaller fleet per type.** 49 NPC drivers spread over six types leaves fewer of each. Premium (3 drivers) can say "No cars nearby" in a quiet corner of the city.
- **Re-typing an existing database:** NPC drivers on a trip during the upgrade keep their old type until driver-service starts again after their trip ends.
- **More geometry per vehicle.** The figures add about 1,500 triangles per bike or auto, which is still lighter than the trees around it.
