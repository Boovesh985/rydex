# 17. The chase camera and a life-size street

Date: 2026-10-16 · Status: Accepted (extends ADR 0015 and ADR 0016)

## Context
The user asked for:
- proper mouse and touch gestures in realistic mode: easy zoom in and out, rotation;
- proper camera angles and a proper car-trailing camera;
- 3D elements with more detail and realism;
- smooth rendering with no glitches.

Trying it showed what was wrong:
- **The chase camera cancelled every gesture.** It moved the camera with `map.jumpTo` on every frame, and MapLibre's `jumpTo` calls `stop()`, which cancels running animations and every gesture in progress. While the camera followed a car, the wheel, pinch, double-click and the +/- buttons were cut off each frame. Turning or tilting the map stopped following altogether.
- **The camera froze between fixes.** It was only updated inside the car's glide, so it froze between GPS fixes (and froze mid-turn). Its smoothing was per frame, so it behaved differently at 30, 60 or 144 fps.
- **The car was 12 times life size.** At the chase zoom (17.4) the car was scaled to stay 54 px long, about 47 m, as big as the buildings. The base style's roads stop widening at zoom 20, so at street level a real-size car would be wider than the road.
- **Clouds covered nearby objects.** The sky overlay (sun, clouds, stars) was a DOM layer on top of the canvas, so it painted over trees and lamps near the camera that rose above the horizon. It also updated at 30 fps on its own timer, a frame behind the camera.
- **Switching day and night stalled the map.** The building colour was data-driven and changed with day and night, which made MapLibre re-parse every tile in its single worker. Night buildings stayed pale until it finished.
- **Scenery went missing and lamps shifted.** Scenery was only re-placed on `idle`, which never fires while a car is followed, so trees ran out after a kilometre. Lamps were spaced from each road piece's first vertex, so rebuilds moved them or doubled rows.
- **The trip page's progress bar jumped.** It matched the car to the planned route with an unconstrained nearest point, and showed 100% whenever the driver took another road near the route's end.

## Decision

### The chase camera owns the camera while it follows (`map/followCamera.ts`)
- **Input:** while following, MapLibre's handlers (drag pan, drag rotate, scroll and pinch zoom, double-click, keyboard, box zoom) are switched off, and the camera reads pointer, wheel and key events itself. They are handed back when it stops.
  - Drag (mouse or one finger) looks around the car and tilts. The look-around springs back behind the car 2.6 s after the last touch.
  - Wheel, pinch or a trackpad pinch zooms; two fingers turn.
  - Double-click resets the view.
  - Arrows look and tilt, +/- zoom, C changes the camera, R resets.
- **One loop:** a requestAnimationFrame loop reads the car's shown pose every frame.
  - Everything is smoothed against the frame time (`damp`, `dampAngle`), so it moves the same at any frame rate.
  - The camera turns with the car a beat later, like a chase camera on a spring.
  - It pulls back up to 0.55 zoom levels at speed.
  - It swoops in from wherever the camera was over 1.7 s.
- **Views**, remembered per browser:
  - chase: zoom 21.2, pitch 70;
  - high chase: 19.7, 58;
  - street level: 22.1, 78;
  - top-down: 19.3, 0, heading-up.
- **Without a followed car,** the free camera keeps MapLibre's gestures. The view button steps through tilts (72, 50, 0).
- **HUD buttons:** zoom and view buttons replace MapLibre's navigation control in realistic mode. They reach whichever camera drives the map through a small command bus.
- **Gesture hint:** a hint shows the gestures once per session for each mode.
- **Glide pacing:** the car's glide between fixes is paced by the measured fix interval (×1.08), so it keeps rolling instead of stopping to wait for a late fix.

### Life-size street
- **Zoom limits:** realistic mode goes to zoom 23; 2D maps stop at 20.
- **Roads (`theme.ts`):** in realistic mode the base map's roads get real widths at street level. `worldWidth()` keeps the map's pixel widths when zoomed out and doubles with every zoom level towards the real width.
  - Main roads are 12 m of asphalt in 17 m of pavement; minor roads are 6.5 m.
  - These are zoom-only expressions, so no tile is re-parsed.
  - Roads turn asphalt grey with pale pavements, with dashed lane lines (15 cm paint, 3 m dashes) on main roads from zoom 17.
  - The route line, the traffic band and the driven-path trace get real widths too: 2.6 m, 1.6 m and about 0.9 m.
- **Cars:** the focus car is drawn at 1.12× life size at street level. Cars still grow when zoomed out (a fleet car at zoom 13 would be a pixel).
  - Small parts (lights, plates, shut lines, spinning wheels) are not drawn on cars scaled up more than 2.4×, which keeps the operations map's 40 cars cheap.

### More detailed models
- **Cars (`car3d.ts`):**
  - **Body:** a side profile extruded across the width, with wheel arches cut out and lined. On top sits a glass cabin with a raked windscreen and a roof panel on pillars. It has bumper intakes, a grille and door shut lines and handles, mirrors, Indian commercial number plates (black on yellow), a lit TAXI sign, and five-spoke rims.
  - **Paint and glass:** clearcoat paint and glass reflect a generated sky-and-street environment (PMREM), built once inside MapLibre's render, after which MapLibre resets its GL state.
  - **Lighting:** cars are lit from where the sun or moon really is.
  - **Motion:** the wheels roll with the distance actually covered, so they never slide. The front wheels steer into turns, the body leans out of a corner and dips under braking. Brake lights come on when slowing or stopped, and the indicator blinks through a turn.
- **Trees (`scenery.ts`):** smooth broadleaf crowns of a few lumpy lobes (darker underneath), and coconut palms (28% of street trees, 20% in parks) with ringed, leaning trunks, drooping fronds and coconuts. The crowns sway a few centimetres in a vertex shader, each tree out of step.
- **Lamps and street trees** are placed where a road crosses a fixed world grid (`sceneryPlacement.placeAlong`). The same road gives the same spots however tiles cut it or whichever way it runs, so rebuilds never move a lamp or double a row. Placement keeps the nearest first before capping.
- **Re-placing scenery** runs in 4 ms slices between frames, and is triggered by moving 330 m, new tiles, or a once-a-second check while following.
- **Lighting from the real camera (`threeCamera.syncCamera`):** the three.js layers recover the real camera position from MapLibre's matrix (the point it sends to w = 0) and split it into a view and a projection. Before, three.js put the eye at the scene origin on the ground, so specular highlights and reflections were wrong.

### Sky, night and smoothness
- **Sky behind the map:** in realistic mode the map's own sky is see-through, and `Atmosphere` draws the sky gradient, sun or moon, stars and clouds behind the canvas. Anything standing above the horizon now covers the clouds. The ground's fog fades into the same horizon colour.
  - It updates on MapLibre's `render` event, in step with the camera, and only when the camera moved or the clouds drifted.
- **Night:** building facades are set once, with eight Chennai creams and pastels per building and glassy towers. Night darkens them through MapLibre's light colour, which re-parses nothing, so the switch is instant.
- **Rendering cost:**
  - the pixel ratio is capped at 2;
  - HUD panels over the map no longer use backdrop blur (it was recomputed every frame over a canvas that redraws every frame);
  - the car layer re-centres its origin every 2 km, for precision at street level.
- **Trip page:** follows the road like the rider's screen (`useFollowedRoute`: forward-only matching, rerouting, progress that never goes down). The driven trace starts after its last jump (over 250 m between fixes).

## Consequences
- Following a car is now a game-style chase camera: dragging looks around the car instead of panning the map. To pan, the viewer turns off Follow or shows the whole route. The on-screen hint says so.
- At street level the world is to scale, so a building between the camera and the car can hide the car while looking around. The look-around springs back behind the car.
- Tall objects are drawn at real size. The renderer does more work per frame (about 400 triangles per broadleaf tree, 300 per palm), and hiding details on scaled-up cars keeps the operations map cheap.
- MapLibre's `transform` is still read for the camera distance (as in ADR 0016). The follow camera depends on `jumpTo` stopping handlers only while it has switched them off.
