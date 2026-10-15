# 13. Web app and driver simulator

Date: 2026-10-14 · Status: Accepted (maps superseded by ADR 0015: MapLibre vector maps and the realistic 3D mode replace Leaflet)

## Context
Phase 6 adds the React web app (master prompt §11) and the driver simulator (§12). The goal: a full ride
works in a browser, with simulated drivers.
- The app serves riders, drivers and admins.
- It talks to four services and a WebSocket.
- It runs on Vercel's free plan in production (Phase 10).

## Decision

### One origin, routed by path prefix
The browser sees a single API origin. Each path prefix goes to the service that owns it:

| Prefix | Service |
|---|---|
| `/api/admin/payments`, `/api/payments` | payment-service |
| `/api/admin/stats`, `/api/admin/demand` | ride-service |
| `/api/admin`, `/api/drivers` | driver-service |
| `/api/tracking`, `/ws` | tracking-service |
| `/api` (auth, me, places, rides) | ride-service |

- **Locally,** the Vite dev server proxies these (`frontend/vite.config.ts`, first match wins). No CORS is involved, and the WebSocket keeps the `localhost:5173` origin that tracking-service allows.
- **In production,** Caddy does the same routing (Phase 8). `VITE_API_URL` and `VITE_WS_URL` point the Vercel build at it, and CORS allows only the Vercel domain.
- The simulator and the smoke test use the same table.

### Stack and structure
- React 19, Vite 8, TypeScript, Tailwind 4, React Router, TanStack Query (server state), Zustand (session and toasts), react-leaflet with OpenStreetMap tiles, and `@stomp/stompjs`.
- Every dependency is pinned to a release at least two weeks old.
- TypeScript stays on 6.0, the newest release typescript-eslint supports.
- Each screen is its own lazily loaded chunk, so a rider never downloads the admin map.

### Sessions
- The access and refresh tokens are kept in `localStorage`.
  - This is a deliberate trade-off. Cookies would need a backend change (a same-site cookie and CSRF protection) for a cross-origin Vercel to OCI setup.
  - The cost is that tokens are exposed to XSS. Mitigations: React escapes all output, no HTML is injected, refresh tokens rotate and are revoked on logout, and access tokens last 15 minutes. A Content Security Policy follows in Phase 8.
- The client refreshes the access token a minute before it expires, and once on a 401. Concurrent callers share one refresh.

### Real time
- There is one STOMP connection per tab ([contracts/websocket.md](../../contracts/websocket.md)).
  - Every (re)connect gets a fresh token in `beforeConnect`.
  - Reconnects back off exponentially, from 1 s up to 30 s.
  - Subscriptions survive reconnects.
- Pushes missed while disconnected are not replayed. After every reconnect, screens re-read their state over REST:
  - the ride;
  - its live location;
  - the driver's open offer.
- Polling is the safety net: the ride every 15 s, and the open offer every 10 s while disconnected.
- Each ride or payment notification shows a toast and refreshes the queries for that ride.
- **Driver locations:** the browser sends its position every 3 s over the socket. When the socket is down it sends it over REST to driver-service, so matching still sees the driver.
- **The rider's map:** the car glides between fixes and turns the short way round.
  - A timer lands it on each fix even when the browser pauses animation frames (background tabs).
  - For the same reason, map tiles and view changes are not animated.

### Small backend additions
- `GET /api/drivers/for-ride/{rideId}` (driver-service): the driver card for a ride.
  - It returns name, vehicle and rating, but never position or status.
  - Only the ride's rider, its driver or an admin can read it; anyone else gets 404.
- `GET /api/places/route` (ride-service): the cached OSRM route between two points, used for the live-ride and driver maps. It falls back to a straight line, like quotes do.
- `GET /api/admin/stats` and `GET /api/admin/demand` (ride-service): ride KPIs, and requests counted per ~500 m cell for the heatmap.
  - These live in ride-service because it owns rides.
  - The admin grid and live drivers stay in driver-service.

### Driver simulator (`simulator/`)
- It is Python asyncio with `httpx` and `websockets`, plus a minimal STOMP client of its own, so it needs no extra dependency.
- It uses the seeded driver accounts and the public APIs only.
- **Sign-ins are paced** at 15 a minute in total (ride-service allows 20 per IP), so a person can still sign in from the same machine.
- **Ride PINs:** a simulated driver cannot ask a rider for the PIN, so it derives it like `OtpService` does (HMAC of the ride id with `OTP_SECRET`).
  - Locally this uses the development default.
  - Against a deployment it only works with that deployment's secret, which only its operator has.
  - Production secrets are enforced in Phase 8.
- **Movement:** cars follow OSRM routes from ride-service's cache.
  - A start point off the road network (the sea east of Chennai) snaps to the nearest road.
  - `--park` avoids cruising, to spare the public OSRM server.
- **"Simulate driving" switch:** the web app's driver screen has one too (dev builds, or `VITE_ENABLE_SIMULATOR=true`). It drives the route in the browser, for a human-driven demo without a phone.

### Look
- **Palette:** chalk and ink surfaces, auto-rickshaw yellow for the one main action per screen, Marina teal for everything live (routes, the car).
- **Fonts** (Google Fonts):
  - Archivo, expanded, for display;
  - Atkinson Hyperlegible Next for text, readable at a glance in a car;
  - IBM Plex Mono for fares, PINs and plates.
- **Signature element:** a fare-meter readout (ink housing, glowing digits) for the one number that matters now (fare, ETA, distance). Vehicle numbers are drawn as Indian HSRP number plates.

### Tests
- **Unit and component tests** (Vitest and Testing Library) cover:
  - the API client (refresh, errors);
  - geometry and polylines;
  - booking (idempotency key reused on retry);
  - the live ride (location pushes, PIN, payment states);
  - the offer countdown.
- **One Playwright test** runs against the running backend. A rider books in Chromium and follows the ride to the fare. The test plays the driver over the API.
- **CI jobs:**
  - a frontend job (lint, types, tests with coverage, build) and a simulator job (pytest);
  - the e2e job also runs the Playwright test and a 45-second simulator run.

## Consequences
- **Tile usage:** OpenStreetMap's tile servers are fine for a low-traffic demo but have a usage policy. Heavy use would need a free-tier tile provider or self-hosted tiles.
- **Phase 8 checklist:**
  - the Caddyfile must reproduce the routing table above exactly;
  - Razorpay Checkout (`checkout.razorpay.com`) and Google Fonts must be allowed by the CSP.
- **Development secret only:** the simulator's PIN shortcut works because the development OTP secret is public. Phase 8 makes production refuse to start with it.
- **One session per browser:** riders and drivers testing together need two browsers or a private window.
