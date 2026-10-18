# 16. The living city: NPC drivers and riders, traffic, and a game-like 3D city

Date: 2026-10-15 · Status: Accepted (extends ADR 0013 and ADR 0015)

## Context
Rydex is a learning and portfolio project: it has no real drivers or riders. The user wants the app to look alive:
- automated drivers and riders, "like NPCs in a game", going about the city on their own;
- the admin map showing every car moving: free cars cruising, cars heading to pickups, cars carrying riders, and riders waiting;
- the rider's booking screen showing the free cars around them, moving;
- Google-style traffic (green, orange, red) in 2D and 3D;
- a 3D view that looks like a simple game: trees, street lights, sky, sun or moon, clouds. Shadows, reflections, physics, people and other vehicles are out of scope.

Only one rider account and one driver account stay manual, for the user's own testing.

## Decision

### NPC accounts
- **Accounts:** the demo seed now has 60 riders. `rider1@rydex.dev` and `driver01@rydex.dev` belong to the user. Riders 2-60 (with realistic names) and drivers 02-50 are NPCs.
- **The `simulated` flag:**
  - It is a column on `users` (migration V8). The seed sets it; sign-up never does.
  - A ride booked by an NPC rider copies the flag onto `rides.simulated`, into the ride view (the admin's "NPC" badge), and into every `ride.events` payload (`RidePayload.simulated`).
- **Payments:** payment-service charges a simulated rider's fare or fee through the mock provider, whatever `PAYMENT_PROVIDER` says, so the user's Razorpay test account never sees orders for pretend people. Their real rides still go through Razorpay.

### The simulator runs the city
`python simulate.py --city` (40 drivers and 30 riders around T Nagar, 7 km) builds on the Phase 6 driver simulator:
- **NPC riders (`rydex_sim/rider.py`):**
  - Each lives near one of 28 Chennai places (`places.py`), books a trip to another place 1.5-9 km away, and picks Economy, Comfort or XL in a 65/25/10 mix.
  - They wait, give up after about 2.5 minutes without a match, and read out the PIN when the car arrives.
  - After the ride they carry on from the drop, and book again after an exponential idle time (mean 150 s).
- **NPC drivers:**
  - Free drivers cruise between streets near real places, not random points that may fall in the sea.
  - They report GPS every 3 s, and slow down in traffic (below).
- **The PIN, properly:** an NPC rider "says" the PIN on an in-process board (`PinBoard`), and the NPC driver types what it heard. Only when the rider is a real person, who cannot reach the board, does the driver fall back to the development OTP secret, after the boarding wait.
- **Sign-ins** share the existing budget of 15 per minute, so the whole city is in after about 5 minutes. Drivers sign in before riders.

### Playing alongside the NPCs
The user tests every pairing by hand: an NPC rider with them driving, an NPC driver with them riding, NPCs only, and them on both sides.
- **People first in matching:**
  - Forty NPC drivers would win nearly every ride, so driver-service offers a ride to a real person online nearby before any NPC.
  - That covers a real rider's ride anywhere in the search area (8 km), so `rider1` gets `driver01`, and an NPC rider's ride within the first radius (3 km).
  - Once the person has been offered the ride, normal matching carries on.
  - driver-service knows who is an NPC from `drivers.simulated` (V5, set by its seed for drivers 02-50) and `match_jobs.simulated_rider` (from `RidePayload.simulated`). With no NPCs among the candidates, matching is unchanged.
- **An NPC rider reads the PIN out to a person:**
  - An NPC rider has no screen to show the PIN on.
  - Once the car is at the pickup (DRIVER_ARRIVED), ride-service includes the PIN in the ride view for the assigned driver of a simulated ride, and only then.
  - The driver's screen shows what the rider "says", and the driver still types it in to start.

### Nearby cars for riders
`GET /api/drivers/nearby?lat&lng` (driver-service) returns up to 6 free cars per ride type within 3 km, nearest first, read from the matching GEO index:
- **No driver identities:** each car carries an opaque id, an HMAC of the driver id with a key made at startup. It stays stable between polls (so the map can glide the car) and means nothing else.
- **On the rider's map:** the booking screen polls it every 3 s around the pickup and animates the cars, with a "6 cars nearby · closest 2 min" chip. The live-ride screen does the same while a ride is still searching.

### Traffic
- **Roads:** `infra/scripts/build-traffic-roads.py` fetches Chennai's main roads (motorway to tertiary) once from OpenStreetMap's Overpass API, in tiles, cached in `.cache/`. It simplifies them, cuts them into ~450 m segments, and commits `frontend/public/data/traffic-roads.json`. Each segment has a road class and a stable hash.
- **Model:** no free live-traffic feed exists, so traffic is simulated with one formula, implemented in both `map/traffic.ts` and `rydex_sim/traffic.py`. Shared test vectors pin the two implementations together.
  - Congestion = Chennai's demand by hour (rush peaks around 09:15 and 18:45 IST, a quiet night) × road class × the segment's hash, plus a slow wave.
  - The hash is mostly per road, so a road is busy along its length, the way Google's traffic looks.
- **The map:** the web app draws it Google-style:
  - free green, slow orange, heavy red, jammed dark red;
  - small roads only when they are not free-flowing;
  - under the route, the buildings and the labels;
  - refreshed every minute.
- **The simulator** slows a car on a congested segment to as little as 22% of its speed, so the red stretches are where its cars crawl.
- **The switch:** traffic is on by default, and a switch on every map turns it off (remembered per browser).

### A game-like 3D city
- **Scenery:** a second three.js custom layer (`map/scenery.ts`, loaded with realistic mode):
  - **Trees:** low-poly, instanced, in parks and woods (scattered on a jittered world grid) and along streets with gaps.
  - **Street lights:** along trunk to tertiary roads every 34-44 m, alternating sides, arms over the road. At night their lamps glow and throw a pool of light on the road.
  - Everything is placed from the map's own vector tiles within 1.1 km of the view. Placement is deterministic, so nothing jumps when tiles reload. It is rebuilt when the view moves on.
  - At most 2,600 trees and 1,100 lamps, drawn as instanced meshes.
- **Sky:** an overlay in the band above the horizon (computed from the camera) shows:
  - the sun where it really is over Chennai (a simple solar model), or the moon at night;
  - stars that twinkle;
  - 22 clouds drifting with the wind.
  The 3D light comes from the same sun position, so buildings are lit from the right side.
- **Camera:** realistic mode tilts further (66°, the chase camera 74°; MapLibre's limit is raised to 80°), so the sky is in view the way it is in a driving game.
- **Day and night:** by the clock (day 6 am to 6 pm, night 6 pm to 6 am), for 2D and 3D alike. One switch on every map flips it by hand; the choice holds until the clock's next change (6 am or 6 pm), so the clock always takes over again. (Before 2026-10-18 the switch cycled auto, always day and always night, and a forced look stuck for good.)

### The admin map
The admin map joins driver-service's live drivers with ride-service's rides in flight (`pages/admin/city.ts`) to show:
- **Cars by duty:** free cars plain; cars heading to a pickup with an amber ring and a dashed line to the rider; cars carrying a rider with a teal ring and a line to the drop.
- **Riders waiting** at their pickups: searching (pulsing), driver on the way, boarding. Clicking one opens the trip.
- **Counters** for the four.
Layers (drivers, riders, trips, geohash grid, demand) can be switched off one by one, in 2D or 3D.

## Consequences
- **No hosting cost:** the whole city runs on the existing free stack plus a 1 MB static file. Overpass is only called by the build script.
- **Kafka:** the city made the local single-node Kafka miss its own controller heartbeats on a busy 8 GB laptop. Fenced, it reloaded every partition, which burned the CPU that the next heartbeat needed: a loop of `NOT_COORDINATOR` errors, with ride events minutes late. The compose file now gives the broker a 30 s session (heartbeat every 3 s). A single node gains nothing from a quick fence.
- **Load:** the simulator is the heaviest client: 70 accounts, about 13 location updates a second, and up to a ride booking a minute per rider. It is still light for one VM. It is a development and demo tool and never runs in CI.
- **Fake traffic:** the traffic is a believable simulation, not real data. The UI says so in the admin legend, and this ADR records it.
- **Accounts:** NPC accounts are ordinary accounts with a flag. Turning a seeded NPC into a real tester is one SQL update.
- **GPU load:** realistic mode now draws more: trees, lamps, a taller view. On weak GPUs it is still optional, and 2D stays the default.
