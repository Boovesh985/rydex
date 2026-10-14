# Driver simulator

Fake drivers for demos, screenshots and load: each one signs in with a seeded account, goes online, cruises
around the city along real roads, accepts ride offers (most of them), drives to the pickup, starts the trip with
the rider's PIN, drives to the drop and completes the ride. Positions go to tracking-service over the same
WebSocket (STOMP) API the app uses, every 3 seconds.

```powershell
cd simulator
python -m venv .venv; .venv\Scripts\Activate.ps1      # optional
pip install -r requirements.txt
python simulate.py --drivers 50 --city-center 13.0827,80.2707
```

Stop it with Ctrl+C: the drivers go offline first.

| Option | Default | |
|---|---|---|
| `--drivers N` | 10 | 1-50, taken across ride types (any N includes ECONOMY, COMFORT and XL) |
| `--city-center lat,lng` | Chennai Central | where the drivers spread out |
| `--radius-km` | 4 | how far from the centre they roam |
| `--accept-rate` | 0.85 | chance of accepting an offer; declined ones go to the next driver |
| `--speedup` | 1 | drive this many times faster than real time (40 km/h on a trip) |
| `--park` | off | idle drivers stay put instead of cruising (far fewer route lookups) |
| `--api URL` | local ports | one origin that routes `/api/*` by prefix, e.g. the deployed backend |
| `--otp-secret` | the dev secret | see "Ride PINs" |
| `--auth-per-minute` | 15 | sign-ins per minute for all drivers together |
| `--seed` | random | repeatable runs |

**Sign-ins are paced.** ride-service allows 20 sign-ins a minute per IP address; the simulator stays at 15 so you
can still sign in from the browser. 50 drivers therefore take about 3.5 minutes to come online.

**Ride PINs.** A real driver hears the 4-digit PIN from the rider. A simulated driver has nobody to ask, so it
computes the PIN the way ride-service does, from the ride id and ride-service's `OTP_SECRET`. Locally that is the
development default, which the simulator uses unless told otherwise. Against a deployment, pass that
deployment's secret (`--otp-secret` or the `OTP_SECRET` variable); with `--otp-secret ""` the drivers wait at the
pickup until the rider cancels.

**Routes** come from ride-service's cached OSRM proxy (`GET /api/places/route`). The public OSRM server allows
about one request a second, so for large or long runs use `--park`.

Tests: `pip install -r requirements-dev.txt; python -m pytest`.
