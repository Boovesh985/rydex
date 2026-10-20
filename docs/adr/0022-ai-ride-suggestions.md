# 22. AI ride suggestions

Date: 2026-10-18 · Status: Accepted (master prompt §9, Phase 7)

## Context
- The rider's booking screen should carry a "smart suggestion": which ride type, when to book, where to be picked up and which way to go, with plain reasons.
- Constraints: free tier only (no paid LLM, no weather or traffic feed), a small VM, and booking must never wait for or depend on it.

## Decision
- **A separate internal service, `ai-service`** (Python 3.12, FastAPI, LightGBM, :8085). It is never exposed by the proxy; only ride-service calls it.
- **ride-service asks, with every rider's estimate** (`POST /suggestions`, contract `contracts/ai/suggestions.schema.json`), and merges the answer into the estimate as `suggestion`.
  - The request carries what ride-service already knows: the quoted options (fare, fare without surge, closest-car ETA, upgrade), the route and OSRM's alternatives, the surge and its demand/supply counts, and the rider's last 20 completed trips. So ai-service needs no database of its own and the suggestion agrees with the prices on screen.
  - **Never in the way:** a 300 ms read timeout (150 ms to connect) and a Resilience4j circuit breaker (`ai`, 60 s open). Any failure means an estimate without a suggestion.
  - The living city's automated riders get none (nothing to learn from logging them).
- **Live features from Kafka:** ai-service consumes `ride.events` (RIDE_REQUESTED, per geohash-6 cell, 15-minute buckets) and `driver.location` (free drivers by their last cell) into Redis (`ai:*` keys). The demand forecast for a later pickup is an EWMA of the last two hours, shaped by the city's daily curve. It starts at the latest offsets and keeps retrying while Kafka is down.
- **Two LightGBM regressors** (wait minutes, surge), on hour, weekday, holiday, demand, supply, their ratio, ride type, trip length and a time-of-day traffic multiplier.
  - **Trained on synthetic data** (`training/generate.py`): rush hours, off days, monsoon rain (not a feature, so it is noise), busy and quiet areas, a fleet that thins out at night. The surge target is ride-service's own formula.
  - `python -m training.train` is deterministic and writes `models/` with `metrics.json`. The models are committed (about 200 KB), so the service starts without training. Held-out MAE: wait 1.6 min against 3.5 min for a constant guess; surge 0.04 against 0.06 (surge is 1.0 most of the time).
- **The engine** (`app/engine.py`), on top of the models:
  - **ride type:** from the rider's history (recency-weighted favourite, price sensitivity); a new rider gets the cheapest type with a short wait; no bikes beyond 15 km. A comparable ride at most 15% dearer that comes at least 5 min sooner replaces the usual one: any bigger class or one class down, never a bike for someone who rides with a seat. The same goes when the usual class is not nearby: the nearest comparable class first, a bike only if nothing else is on its way;
  - **pickup time:** now, +10 and +20 min. The model's change is added to the surge and ETA known now, and a later time is only suggested when it saves at least Rs 15 and 8%, after valuing the wait;
  - **pickup point:** a spot on a main road (the traffic map's OSM roads) in a neighbouring cell, at most about 400 m away, when its free cars make the wait at least 2 min shorter, walk included;
  - **route:** each of OSRM's routes timed through the city's simulated traffic, the formula behind the map's traffic layer and the simulated cars' speeds (`app/traffic.py`, pinned to the web app's and the simulator's copies by shared test vectors): every ~100 m on a mapped main road (within 30 m of it) is slowed by that road's congestion at that moment. The other route is suggested when it is at least 2 min and 5% faster. The roads are points every 10 m in a k-d tree (scipy), so a whole route is looked up in one query, a few ms. Routes without a shape fall back to the time-of-day multiplier. A first version scored routes by their average speed, but OSRM's free-flow speeds are 50+ km/h on nearly every Chennai route, so every route counted as main road and the alternative never won;
  - **reasons:** templates, no LLM.
- **Feedback:** `ai_suggestion_log` (ride-service V12) stores every suggestion shown, with its request and response. Booking with `suggestionId` marks it taken; "Not now" (`POST /api/ai/suggestions/{id}/feedback`) marks it dismissed. The admin KPIs show the share booked with; Grafana follows in Phase 9.

## Consequences
- **Honest limits:** free routing has no live traffic, so traffic is the city's simulated traffic (the same as on the map). The models learn a synthetic city, not real Chennai trips; the log is there to retrain on real feedback later.
- **There is no scheduled booking.** A later pickup time is advice ("book at 10:55 to save Rs 38"); "Use suggestion" applies the ride type and the pickup point.
- **A suggested route is advice too.** The fare is quoted on the fastest route, and the driver navigates as usual.
- **About 10 ms a suggestion** on the laptop (timing the routes through traffic is most of it), well inside the 300 ms budget. Memory: about 150 MB.
- **One more process to run locally** (`uvicorn app.main:app --port 8085`). Without it everything works as before, with no card.
