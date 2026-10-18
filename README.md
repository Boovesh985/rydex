# Rydex

Uber-style ride hailing with live driver tracking and AI ride suggestions, built on free tiers only.
React · Java 21 / Spring Boot 4 · PostgreSQL + PostGIS · Redis · Apache Kafka · WebSockets · FastAPI.

> 🚧 Work in progress. Phases 0–6 (scaffold, ride-service, driver-service, Kafka event flow, live tracking over WebSockets, payments, web app and driver simulator) are done, followed by a security audit (encrypted personal data, Argon2id passwords, account lockout), a realistic 3D tracking mode and the living city (automated drivers and riders, traffic, a game-like 3D city). The full plan is in [docs/MASTER_PROMPT.md](docs/MASTER_PROMPT.md).

## Quick start

Requires Docker and JDK 21; Node.js 22.12+ for the web app and Python 3.12+ for the driver simulator. Maven is not required; use the wrapper.

**Windows (PowerShell)**
```powershell
# 1. Infrastructure: Postgres+PostGIS, Redis, Kafka (topics are created automatically)
docker compose -f infra/docker-compose.yml up -d

# 2. Build and test every backend module (integration tests use Testcontainers)
.\mvnw.cmd verify

# 3. Stable JWT signing key (optional; needs Git Bash). Without it an ephemeral key is used.
& "C:\Program Files\Git\bin\bash.exe" infra/scripts/gen-jwt-keys.sh

# 4. Run ride-service, then open http://localhost:8081/swagger-ui
cd services\ride-service; ..\..\mvnw.cmd spring-boot:run

# 5. In a second terminal, run driver-service (http://localhost:8082/swagger-ui)
cd services\driver-service; ..\..\mvnw.cmd spring-boot:run

# 6. In a third terminal, run tracking-service (WebSocket at ws://localhost:8083/ws)
cd services\tracking-service; ..\..\mvnw.cmd spring-boot:run

# 7. In a fourth terminal, run payment-service (http://localhost:8084/swagger-ui)
cd services\payment-service; ..\..\mvnw.cmd spring-boot:run

# 8. In a fifth terminal, run the web app, then open http://localhost:5173
cd frontend; npm install; npm run dev

# 9. In a sixth terminal, bring the city to life: 40 automated drivers and 30 automated riders
cd simulator; pip install -r requirements.txt; python simulate.py --city
```

**The living city (ADR 16).**
- **The NPCs:** with `python simulate.py --city` running, automated drivers and riders go about Chennai on their own.
  - Free cars cruise, and riders book trips between real places.
  - Drivers pick them up, hear their PIN, drive them through the traffic and get paid.
- **Your accounts:** `rider1@rydex.dev` and `driver01@rydex.dev` are left for you.
- **The operations map** shows it all live:
  - free cars;
  - cars heading to pickups (amber, with a line to the rider);
  - cars carrying riders (teal);
  - riders waiting at their pickups.

**Ride types.** One app for every way people get around the city (ADR 18), cheapest first:
- **Bike:** a motorbike taxi for one, helmet provided; it weaves through traffic.
- **Auto:** an auto rickshaw with three seats.
- **Cabs:** Economy (hatchback), Comfort (sedan), XL (six-seat MUV) and Premium (premium sedan).

**Try a ride.**
- **Book:** sign in as `rider1@rydex.dev` (the demo buttons fill it in). The free bikes, autos and cabs around you drive about on the map. Search or tap the map for a drop, pick a ride type and book.
- **Ride:** a nearby automated driver accepts and drives to you. They wait at the pickup for your PIN, then start the trip and drive you to the drop. The car moves live on your map.
- **Drive:** sign in as `driver01@rydex.dev` in a second browser (or a private window), switch on **Simulate driving** and go online. Real people come first in matching:
  - you get the offer for any automated rider's ride within 3 km before the automated drivers do;
  - at the pickup, the automated rider "reads out" their PIN on your screen, and you type it in.
- **Both sides yourself:** with driver01 online, rider1's rides go to you first (anywhere within 8 km), as long as you book driver01's ride type (Economy, unless you change it: while offline, **Change vehicle** turns driver01 into a bike, an auto or any cab).
- **Watch:** sign in as `admin@rydex.dev` for the operations map: the living city, the geohash grid matching uses, demand and KPIs, and every ride in flight (open one to watch it live).
- **Ride along:** on the operations map, click any vehicle. In Realistic 3D the chase camera rides behind it, and its driver and trip show live at the side. Close it (X or Esc) to return to the view you had.

**Realistic 3D.** On the booking map, a live ride, the driver dashboard, a trip page or the operations map, press **Realistic 3D**:
- the map tilts, buildings rise, and at street level everything is life-size: asphalt roads with pavements and lane lines;
- streets get broadleaf trees, coconut palms swaying in the breeze, and street lights;
- the sun (or the moon and stars) sits where it really is over Chennai, and clouds drift by behind the buildings;
- the vehicle becomes a detailed 3D model that steers into turns and shows brake lights and indicators:
  - a cab (number plates, rims, a lit taxi sign) leans out of corners;
  - a bike's rider wears a helmet, and a pillion rides behind on a trip; the bike leans into the turns;
  - an auto has its driver up front under the canopy, and passengers on the back bench during a trip;
- a chase camera rides behind it, with a speedometer and the ETA overhead.

Moving the camera:
- **Following a car:** drag to look around it (it swings back behind the car), scroll or pinch to zoom, double-click to reset. The camera button (or C) switches between chase, high chase, street level and top-down. Arrow keys look and tilt.
- **Free camera** (the operations map, the booking map, or with Follow off): drag to move, right-drag or Ctrl+drag to turn and tilt, scroll to zoom. The camera button steps between street tilt, bird's eye and top-down.

Every map has two switches by its zoom buttons:
- **traffic:** Google-style green, orange, red and dark red on the main roads. It is simulated, and the automated cars slow down in it.
- **day and night:** day from 6 am to 6 pm and night from 6 pm to 6 am by the clock; the switch on every map flips it by hand until the next 6 o'clock. The night city has glowing street lights and headlights.

Maps are MapLibre vector maps on free OpenFreeMap tiles. The 3D vehicles and scenery are drawn with three.js (ADR 15, ADR 16, ADR 17, ADR 18).

The web app talks to one origin: the Vite dev server forwards each `/api` prefix and `/ws` to its service (ADR 13).
The simulator signs in at most 15 accounts a minute, so the whole city takes about 5 minutes to come in. Automated
riders read out their PIN to automated drivers. For your own rides the drivers fall back to ride-service's
development OTP secret after a short wait (`--otp-secret` for any other). Automated riders always pay through the
mock provider, so they never create Razorpay orders. See [simulator/README.md](simulator/README.md).

Payments use a mock provider by default (every charge succeeds at once). To use **Razorpay test mode** instead,
copy `services/payment-service/.env.example` to `services/payment-service/.env` (git-ignored), fill in your test
keys and restart payment-service. No real money moves. The rider pays in Razorpay Checkout from the ride screen
("Pay"); choose Netbanking (any bank, then Success) or a
[test card](https://razorpay.com/docs/payments/payments/test-card-details/). Avoid Wallet: Razorpay test mode would
not refund a wallet payment. `http://localhost:8084/dev/checkout.html` pays one ride without the web app. See ADR 12.

Already have a database volume from before Phase 4? Add the newer schemas and roles once (safe to repeat):
```powershell
Get-Content infra\postgres\initdb\01-init.sh -Raw | docker compose -f infra/docker-compose.yml exec -T postgres bash -s
```

**Check that it works.** With the infrastructure and the four services running, this tests everything built so
far and prints PASS/FAIL per check. It runs a full ride across the services over Kafka (book, offer, accept,
arrive, OTP start, complete), the same ride live over WebSockets (the offer pushed to the driver, accepted over
the socket, notifications and the driver's position streamed to the rider), and payments (the fare charged, a late
cancellation fee, an admin refund), then what the web app reads (driver card, routes, admin KPIs) and, if the dev
server runs, its proxy to every service. CI runs it on every push, followed by a Playwright test that books and
follows a ride in Chromium (`cd frontend; npx playwright install chromium; npm run e2e`):
```powershell
powershell -ExecutionPolicy Bypass -File infra\scripts\smoke-test.ps1
```
In Razorpay test mode, add `-PayInBrowser`: it opens the test checkout to pay the cancellation fee, then refunds it.

**macOS / Linux / Git Bash**
```bash
docker compose -f infra/docker-compose.yml up -d
./mvnw verify
bash infra/scripts/gen-jwt-keys.sh
cd services/ride-service && ../../mvnw spring-boot:run
cd services/driver-service && ../../mvnw spring-boot:run   # second terminal
cd services/tracking-service && ../../mvnw spring-boot:run # third terminal
cd services/payment-service && ../../mvnw spring-boot:run  # fourth terminal
cd frontend && npm install && npm run dev                     # fifth: http://localhost:5173
cd simulator && pip install -r requirements.txt && python simulate.py --city      # sixth
```

**Security (ADR 14).** Emails and phone numbers are encrypted in the database (AES-256-GCM; sign-in finds accounts
by a keyed hash), passwords are hashed with Argon2id, and ten wrong passwords lock an account for 15 minutes.
Locally the service uses published development keys and says so in its log. A deployment sets its own and
`RYDEX_STRICT_SECRETS=true`, which refuses to start with any development secret:

| Variable | What | Generate with |
|---|---|---|
| `PII_DATA_KEY`, `PII_INDEX_KEY` | personal-data encryption and lookup keys (different) | `openssl rand -base64 32` |
| `OTP_SECRET` | ride PIN secret, 32+ characters | `openssl rand -base64 32` |
| `JWT_PRIVATE_KEY_LOCATION`, `JWT_PUBLIC_KEY_LOCATION` | token signing key pair | `infra/scripts/gen-jwt-keys.sh` |
| `API_DOCS_ENABLED` | `false` hides Swagger UI on a public deployment | |

Back up `PII_DATA_KEY` with the database: without it the stored emails and phone numbers cannot be read.

Demo accounts (seeded): `admin@rydex.dev`, `rider1..60@rydex.dev`, `driver01..50@rydex.dev`, all with password `Rydex@123`.
Drivers 01–12 drive ECONOMY, 13–19 COMFORT, 20–23 XL, 24–26 PREMIUM, 27–39 BIKE and 40–50 AUTO. All of them start offline. `rider1` and `driver01` are for
testing by hand; riders 2–60 and drivers 02–50 are the automated (NPC) accounts the simulator plays.

Every service trusts the same token, so sign in once on ride-service (`POST /api/auth/login`) and paste the
`accessToken` into **Authorize** on any Swagger page.

| Component | Address (local) |
|---|---|
| PostgreSQL 16 + PostGIS | `localhost:5432` db `rydex` |
| Redis 7 | `localhost:6379` |
| Kafka 4.3 (KRaft) | `localhost:9092` (host), `kafka:29092` (containers) |
| ride / driver / tracking / payment service | `:8081` / `:8082` / `:8083` / `:8084` |
| Web app (Vite dev server) | `http://localhost:5173` |

## Repository layout

```
libs/common-events/   shared event envelope, event types, topic names, geohash, Redis keys
libs/messaging/       outbox relay, idempotent consumers, retries + dead-letter topics (@EnableMessaging)
libs/presence/        driver presence in Redis (Lua scripts), shared by driver- and tracking-service
services/             ride, driver, tracking, payment (Spring Boot)
frontend/             React web app: rider, driver and admin screens (Vite, Tailwind, MapLibre + three.js, STOMP)
simulator/            the living city: automated drivers and riders for demos and load (Python asyncio)
contracts/            JSON Schemas for Kafka events (ride, payment, driver location), the WebSocket (STOMP) API
infra/                docker compose, PostGIS image, Kafka topic script, smoke test, traffic road builder
docs/adr/             architecture decision records
```

## Decisions
See [docs/adr](docs/adr). Highlights: custom multi-arch PostGIS image (ADR 3), schema-per-service in one Postgres (ADR 4), single-broker KRaft Kafka (ADR 5), geohash matching (ADR 8), outbox + idempotent consumers (ADR 9), recovery from lost events (ADR 10), STOMP live tracking with Redis fan-out (ADR 11), payments with Razorpay test mode and refunds (ADR 12), the web app and simulator (ADR 13), security hardening and encrypted personal data (ADR 14), vector maps and the realistic 3D mode (ADR 15), the living city with NPC drivers and riders, traffic and 3D scenery (ADR 16), the chase camera and a life-size street (ADR 17), bikes, autos and cabs in one app (ADR 18), riding along from the operations map (ADR 19).
