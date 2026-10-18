# Rydex guide

A tour of what runs locally, how to try it, and how to configure it. For setup, see the [README](../README.md#getting-started).

- [Demo accounts](#demo-accounts)
- [The living city](#the-living-city)
- [Ride types](#ride-types)
- [Try a ride](#try-a-ride)
- [Realistic 3D](#realistic-3d)
- [Payments](#payments)
- [Smart suggestions (AI)](#smart-suggestions-ai)
- [Checking that it works](#checking-that-it-works)
- [Security and secrets](#security-and-secrets)
- [Local addresses](#local-addresses)
- [Troubleshooting](#troubleshooting)

## Demo accounts

Seeded accounts, all with the password `Rydex@123`:

| Account | Who |
|---|---|
| `admin@rydex.dev` | operations: the live map, KPIs, refunds, ride-along |
| `rider1@rydex.dev` | your rider account |
| `driver01@rydex.dev` | your driver account |
| `rider2..60@rydex.dev` | automated (NPC) riders played by the simulator |
| `driver02..50@rydex.dev` | automated (NPC) drivers played by the simulator |

Drivers 01–12 drive Economy, 13–19 Comfort, 20–23 XL, 24–26 Premium, 27–39 bikes and 40–50 autos. All of them start offline.

Every service trusts the same token: sign in once on ride-service (`POST /api/auth/login`) and paste the `accessToken` into **Authorize** on any Swagger page.

## The living city

With `python simulate.py --city` running (ADR 16), automated drivers and riders go about Chennai on their own:

- free cars cruise, and riders book trips between real places;
- drivers pick them up, hear their PIN, drive them through the traffic and get paid;
- riders rate their trips.

`rider1` and `driver01` are left for you. The simulator signs in at most 15 accounts a minute, so the whole city takes about 5 minutes to come in. Automated riders read out their PIN to automated drivers; for your own rides the drivers fall back to ride-service's development OTP secret after a short wait (`--otp-secret` for any other). Automated riders always pay through the mock provider, so they never create Razorpay orders. See [simulator/README.md](../simulator/README.md).

The operations map (admin) shows it all live: free cars, cars heading to pickups (amber, with a line to the rider), cars carrying riders (teal) and riders waiting at their pickups.

## Ride types

One app for every way people get around the city (ADR 18), cheapest first:

- **Bike:** a motorbike taxi for one, helmet provided; it weaves through traffic.
- **Auto:** an auto rickshaw with three seats.
- **Cabs:** Economy (hatchback), Comfort (sedan), XL (six-seat MUV) and Premium (premium sedan).

When no Economy cab is free, a Comfort cab may take the ride at the Economy fare, and a Premium one may take a Comfort ride (ADR 21).

## Try a ride

- **Book:** sign in as `rider1@rydex.dev` (the demo buttons fill it in). The free bikes, autos and cabs around you drive about on the map. Search or tap the map for a drop, pick a ride type and book. Home, Work and your recent drops are one tap away.
- **Ride:** a nearby automated driver accepts and drives to you. They wait at the pickup for your PIN, then start the trip and drive you to the drop. The car moves live on your map. Rate the trip at the end.
- **Drive:** sign in as `driver01@rydex.dev` in a second browser (or a private window), switch on **Simulate driving** and go online. Real people come first in matching:
  - you get the offer for any automated rider's ride within 3 km before the automated drivers do;
  - at the pickup, the automated rider "reads out" their PIN on your screen, and you type it in.
- **Both sides yourself:** with driver01 online, rider1's rides go to you first (anywhere within 8 km), as long as you book driver01's ride type (Economy, unless you change it: while offline, **Change vehicle** turns driver01 into a bike, an auto or any cab).
- **Watch:** sign in as `admin@rydex.dev` for the operations map: the living city, the geohash grid matching uses, demand and KPIs, alerts, and every ride in flight (open one to watch it live).
- **Ride along:** on the operations map, click any vehicle (or find one by plate or driver name). In Realistic 3D the chase camera rides behind it, and its driver and trip show live at the side. Close it (X or Esc) to return to the view you had.

## Realistic 3D

On the booking map, a live ride, the driver dashboard, a trip page or the operations map, press **Realistic 3D**:

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

- **Traffic:** Google-style green, orange, red and dark red on the main roads. It is simulated, and the automated cars slow down in it.
- **Day and night:** day from 6 am to 6 pm and night from 6 pm to 6 am, Chennai time; the switch flips it by hand until the next 6 o'clock. The night city has glowing street lights and headlights.

Maps are MapLibre vector maps on free OpenFreeMap tiles. The 3D vehicles and scenery are drawn with three.js (ADR 15–18). The web app talks to one origin: the Vite dev server forwards each `/api` prefix and `/ws` to its service (ADR 13).

## Payments

Payments use a mock provider by default (every charge succeeds at once). To use **Razorpay test mode** instead, copy `services/payment-service/.env.example` to `services/payment-service/.env` (git-ignored), fill in your test keys and restart payment-service. No real money moves.

The rider pays in Razorpay Checkout from the ride screen ("Pay"); choose Netbanking (any bank, then Success) or a [test card](https://razorpay.com/docs/payments/payments/test-card-details/). Avoid Wallet: Razorpay test mode would not refund a wallet payment. `http://localhost:8084/dev/checkout.html` pays one ride without the web app. See ADR 12.

## Smart suggestions (AI)

With ai-service running, every estimate a rider asks for can come with a **smart suggestion card** above the ride types:
- **Which ride:** what you usually book (from your last 20 trips), or for a new rider the cheapest ride with a short wait.
- **When:** booking in 10 or 20 minutes instead, if the surge is predicted to ease enough to be worth the wait ("book at 10:55, save ₹38").
- **Where:** a short walk (up to about 400 m) to a main road in the next cell, if more free cars there mean a clearly shorter wait.
- **Which way:** OSRM's other route, if this hour's traffic makes it faster.

It also gives plain reasons and how sure it is. **Use suggestion** picks the ride type and moves the pickup; booking then counts as taking it. **Not now** dismisses it. The operations map's KPIs show how many suggestions riders booked with. Automated riders get none.

Run it next to the four services (Python 3.12+):

```bash
cd ai-service
python -m venv .venv && .venv/bin/pip install -r requirements-dev.txt   # Windows: .venv\Scripts\pip
.venv/bin/python -m uvicorn app.main:app --port 8085                     # Windows: .venv\Scripts\python
```

Without it nothing changes: ride-service waits at most 300 ms for an answer and books as before.

How it works (ADR 22):
- **Live data:** ai-service reads ride requests and free drivers from Kafka into Redis, per map cell.
- **Models:** two LightGBM models predict the wait and the surge. They are trained on synthetic data with Chennai-like patterns: rush hours, weekends, monsoon rain.
- **Traffic:** free routing has no live traffic, so traffic is a time-of-day multiplier.
- **Tools:**
  - `GET http://localhost:8085/model/info` shows the model version and its error (MAE) on held-out data;
  - `python -m training.train` retrains the models;
  - `python -m pytest` runs the tests.

## Checking that it works

With the infrastructure and the four services running, the smoke test prints PASS/FAIL per check:

```powershell
powershell -ExecutionPolicy Bypass -File infra\scripts\smoke-test.ps1
```

It runs a full ride across the services over Kafka (book, offer, accept, arrive, OTP start, complete), the same ride live over WebSockets (the offer pushed to the driver, accepted over the socket, notifications and the driver's position streamed to the rider), and payments (the fare charged, a late cancellation fee, an admin refund), then what the web app reads (driver card, routes, admin KPIs) and, if the dev server runs, its proxy to every service. With ai-service running it checks the smart suggestion (shown, booked with, dismissed); without it, that estimates still work. In Razorpay test mode, add `-PayInBrowser`: it opens the test checkout to pay the cancellation fee, then refunds it.

Stop the simulator first: its cruising drivers can win the smoke test's offers.

The Playwright tests book and follow a ride in Chromium, and check what a rider sees when no driver is found:

```bash
cd frontend && npx playwright install chromium && npm run e2e
```

CI runs both on every push.

## Security and secrets

Emails and phone numbers are encrypted in the database (AES-256-GCM; sign-in finds accounts by a keyed hash), passwords are hashed with Argon2id, and ten wrong passwords lock an account for 15 minutes (ADR 14). Locally the services use published development keys and say so in their logs. A deployment sets its own and `RYDEX_STRICT_SECRETS=true`, which refuses to start with any development secret:

| Variable | What | Generate with |
|---|---|---|
| `PII_DATA_KEY`, `PII_INDEX_KEY` | personal-data encryption and lookup keys (different) | `openssl rand -base64 32` |
| `OTP_SECRET` | ride PIN secret, 32+ characters | `openssl rand -base64 32` |
| `JWT_PRIVATE_KEY_LOCATION`, `JWT_PUBLIC_KEY_LOCATION` | token signing key pair | `infra/scripts/gen-jwt-keys.sh` |
| `API_DOCS_ENABLED` | `false` hides Swagger UI on a public deployment | |

Back up `PII_DATA_KEY` with the database: without it the stored emails and phone numbers cannot be read.

## Local addresses

| Component | Address (local) |
|---|---|
| PostgreSQL 16 + PostGIS | `localhost:5432`, database `rydex` |
| Redis 7 | `localhost:6379` |
| Kafka 4.3 (KRaft) | `localhost:9092` (host), `kafka:29092` (containers) |
| ride-service | `http://localhost:8081/swagger-ui` |
| driver-service | `http://localhost:8082/swagger-ui` |
| tracking-service | `ws://localhost:8083/ws` (STOMP, [contract](../contracts/websocket.md)) |
| payment-service | `http://localhost:8084/swagger-ui` |
| ai-service (internal) | `http://localhost:8085/docs`, `/model/info` |
| Web app (Vite dev server) | `http://localhost:5173` |

## Troubleshooting

**A database volume from before Phase 4.** Add the newer schemas and roles once (safe to repeat):

```powershell
Get-Content infra\postgres\initdb\01-init.sh -Raw | docker compose -f infra/docker-compose.yml exec -T postgres bash -s
```

**8 GB of RAM.** Running the four services and `mvnw verify` at once can run out of memory: stop the services before a full build.

**No suggestion card?** Check that ai-service answers at `http://localhost:8085/health`. After it has been down for a while, ride-service waits a minute (its circuit breaker) before asking again. `/model/info` says `"liveFeatures": false` until ride requests or driver positions arrive over Kafka (start the simulator).

**Changed something in `libs/`?** `mvnw spring-boot:run` inside a module reads the libraries from `~/.m2`: run `./mvnw install -DskipTests -pl libs/common-events,libs/messaging,libs/presence` first.
