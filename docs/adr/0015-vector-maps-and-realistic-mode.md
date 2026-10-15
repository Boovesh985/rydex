# 15. Vector maps and the realistic 3D tracking mode

Date: 2026-10-15 · Status: Accepted (supersedes the Leaflet part of ADR 0013)

## Context
The user asked for live tracking that feels realistic: 3D cars and a city in 3D, a "realistic mode" for it, and nicer tracking pages for riders, drivers and admins.

Leaflet with OpenStreetMap raster tiles, the Phase 6 choice, cannot tilt the map, extrude buildings or draw 3D models.

## Decision

### One map engine: MapLibre GL JS 6 on OpenFreeMap tiles
- [OpenFreeMap](https://openfreemap.org) serves OpenStreetMap vector tiles and styles with no key, no account and no usage fee, so the free-tier rule (master prompt §2) holds.
- Its "Positron" style is recoloured at runtime to the Rydex palette (`map/theme.ts`):
  - chalk land, Marina-tinted water, white streets, auto-yellow highways.
- The same map serves both modes, so switching between the 2D view and realistic mode is a camera move, not a page change.
- **Lazy loading:** the map is loaded with the first map screen (MapLibre, about 290 kB gzipped). three.js (about 135 kB gzipped) is loaded the first time someone enters realistic mode.

### Realistic mode
- **Camera:** the map tilts to about 60°. In "follow" mode the camera rides behind the car:
  - its bearing eases towards the car's heading every frame;
  - dragging or rotating the map stops following;
  - a button resumes it, and "show the whole route" frames the trip.
- **City:** buildings are extruded from OpenMapTiles' `render_height`. They rise out of the ground when the mode starts, under a sky with fog, lit by a sun.
- **Night:** after sunset (or with the toggle) the city turns dark, the route glows, and the cars' headlights shine forward.
- **Cars:**
  - They are 3D models drawn by three.js inside MapLibre's own WebGL context (a custom layer), so buildings hide them correctly.
  - The models are built from primitives in code, so there are no model files to download or license.
  - Each ride type has its own look: an auto-yellow Economy taxi with a roof sign, a white Comfort sedan with a teal stripe, a graphite XL MUV.
  - Wheels turn with the car's speed, and the car being tracked stands on a pulsing ring.
  - Cars are scaled with the zoom so they stay readable: real size at street level, larger further out.
- **Heads-up display:**
  - a status capsule with the ETA or the distance;
  - a speedometer;
  - camera and day/night controls;
  - a compact card with the driver and PIN (rider) or the next trip action (driver).
- **Where it is offered:**
  - the rider's live ride;
  - the driver's dashboard while online;
  - the trip page of a ride in progress (drivers and admins);
  - the admin operations map, as a 3D city with the whole fleet as 3D cars.

### Better tracking in 2D too
- **Cars between fixes:** they glide between the fixes, which arrive every 3 s. A fix near the route is snapped to the road and the car follows the route's curves (`PathIndex`), instead of cutting across blocks.
- **Route progress:** the part already driven is greyed out on the route. A progress bar runs from pickup to drop.
- **Admin map:**
  - demand is a heatmap that turns into counted dots when zoomed in;
  - the matching grid is labelled from zoom 14;
  - the fleet glides between updates.
- **Trip page** (`/trips/:rideId`, drivers and admins): the planned route, the path actually driven (from tracking-service), the car live while the ride is on, and the timeline.
- **Stale positions:** a rider's screen also re-reads the driver's position every 10 s and keeps whichever fix is newest. A driver whose WebSocket is down reports over REST, and those reports are not pushed.

### Things learned the hard way
- **MapLibre's tile worker:** Vite pre-bundles `maplibre-gl` and loses its worker file. The worker is imported with `?worker&url` and handed to `setWorkerUrl`, and the build bundles it.
- **Container height:** MapLibre's CSS sets `position: relative` on the map container, so the container needs explicit `h-full w-full`.
- **Sources added during camera animations:**
  - A GeoJSON source added while the camera is animating can be left with tiles stuck in "loading", and the route never draws (MapLibre 6.11).
  - So fits to points are instant in 2D, as they were with Leaflet; only realistic mode's "show the whole route" animates.
  - Sources are created with their data instead of empty-then-`setData`.
- **Headless WebGL:** headless Chromium without a GPU needs `--enable-unsafe-swiftshader` to draw WebGL; the Playwright config passes it.

## Consequences
- **Tiles and fonts:** OpenFreeMap tiles and fonts come from `tiles.openfreemap.org`, and the CSP allows it. If it were ever unavailable, the maps would be blank but every other screen still works. A self-hosted tile server (also free) is the fallback.
- **WebGL is now required for maps:** a browser without it gets a message in place of the map, and booking still works by searching places.
- **Tests:** unit tests keep mocking `map/MapView`. The Playwright test exercises the real map and enters and leaves realistic mode.
