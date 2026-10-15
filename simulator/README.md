# Simulator: the living city

Automated drivers and riders ("NPCs") for demos, screenshots and load (ADR 0013, ADR 0016):

- **Drivers:**
  - Each signs in with a seeded account and goes online.
  - Free drivers cruise between streets near real Chennai places.
  - They accept most offers, drive to the pickup, start the trip with the rider's PIN, drive to the drop and complete the ride.
  - They slow down in the simulated traffic the web app shows.
  - Positions go to tracking-service over the same WebSocket (STOMP) API the app uses, every 3 seconds.
- **Riders:**
  - Each lives near one of 28 places in Chennai and books trips to other places 1.5 to 9 km away (Economy, Comfort or XL).
  - They wait for the car, read out their PIN to the driver when it arrives, ride, pay (always through the mock provider), and carry on from where they were dropped.
  - A rider gives up after about 2.5 minutes without a match.

`rider1@rydex.dev` and `driver01@rydex.dev` are never used by the simulator: they are yours for testing by hand. The NPCs use riders 2-60 and drivers 02-50 (password `Rydex@123`), which the seed marks as simulated.

```powershell
cd simulator
python -m venv .venv; .venv\Scripts\Activate.ps1      # optional
pip install -r requirements.txt
python simulate.py --city                              # 40 drivers and 30 riders
```

Stop it with Ctrl+C: the drivers go offline first.

| Option | Default | |
|---|---|---|
| `--city` | off | the living city: 40 drivers, 30 riders, around T Nagar (7 km); other options apply on top |
| `--drivers N` | 10 (`--city`: 40) | 0-49 NPC drivers, taken across ride types (any N includes Economy, Comfort and XL) |
| `--riders N` | 0 (`--city`: 30) | 0-59 NPC riders booking trips |
| `--city-center lat,lng` | Chennai Central (`--city`: T Nagar) | where everyone spreads out |
| `--radius-km` | 4 (`--city`: 7) | how far from the centre drivers roam |
| `--rider-idle` | 150 | mean seconds a rider stays put between trips |
| `--accept-rate` | 0.85 | chance a driver accepts an offer; declined ones go to the next driver |
| `--speedup` | 1 | drive this many times faster than real time (40 km/h free-flowing) |
| `--no-traffic` | off | drive at free-flow speed everywhere |
| `--board-seconds` | 20 | how long a driver waits at the pickup for a real rider's PIN |
| `--park` | off | idle drivers stay put instead of cruising (far fewer route lookups) |
| `--api URL` | local ports | one origin that routes `/api/*` by prefix, e.g. the deployed backend |
| `--otp-secret` | the dev secret | see "Ride PINs" |
| `--auth-per-minute` | 15 | sign-ins per minute for everyone together |
| `--seed` | random | repeatable runs |

- **Sign-ins are paced.** ride-service allows 20 sign-ins a minute per IP address. The simulator stays at 15, so you can still sign in from the browser. The whole city (70 accounts) takes about 5 minutes to come in, drivers first.
- **Ride PINs:**
  - An NPC rider reads out the PIN to the NPC driver through an in-process board, as a person would in the car.
  - When the rider is a real person (you, as rider1), the simulated driver cannot hear you. After `--board-seconds` it computes the PIN the way ride-service does, from the ride id and ride-service's `OTP_SECRET`. Locally that is the development default.
  - Against a deployment, pass that deployment's secret (`--otp-secret` or `OTP_SECRET`). With `--otp-secret ""`, drivers of real riders wait at the pickup until the rider cancels.
- **Traffic:** drivers read the road network in `frontend/public/data/traffic-roads.json` and apply the same congestion formula as the web app (`rydex_sim/traffic.py` and `frontend/src/map/traffic.ts`, pinned together by shared test vectors). On a jammed main road a car keeps as little as 22% of its speed.
- **Routes** come from ride-service's cached OSRM proxy (`GET /api/places/route`). The public OSRM server allows about one request a second, so for large or long runs use `--park`.

Tests: `pip install -r requirements-dev.txt; python -m pytest`.
