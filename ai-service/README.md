# ai-service

Smart ride suggestions for Rydex (master prompt §9, [ADR 22](../docs/adr/0022-ai-ride-suggestions.md)): which ride type, when to book, where to be picked up and which way to go, with plain-language reasons. Internal only: ride-service calls it with each rider's estimate (300 ms timeout, circuit breaker) and merges the answer in.

```bash
python -m venv .venv
.venv/bin/pip install -r requirements-dev.txt       # Windows: .venv\Scripts\pip
.venv/bin/python -m uvicorn app.main:app --port 8085 # Windows: .venv\Scripts\python
.venv/bin/python -m pytest                           # tests
.venv/bin/python -m training.train                   # retrain the models (deterministic)
.venv/bin/python -m app.contract                     # regenerate contracts/ai/suggestions.schema.json
```

| Endpoint | |
|---|---|
| `POST /suggestions` | a suggestion for one trip ([contract](../contracts/ai/suggestions.schema.json)) |
| `GET /model/info` | model version, training rows, held-out MAE, whether live features are flowing |
| `GET /health`, `GET /metrics` | health, Prometheus metrics (`ai_suggestion_latency_seconds`, `ai_suggestions_total`, `ai_feature_events_total`) |
| `GET /docs` | OpenAPI UI |

Settings (environment): `REDIS_HOST` / `REDIS_PORT` (127.0.0.1:6379), `KAFKA_BOOTSTRAP_SERVERS` (127.0.0.1:9092), `AI_KAFKA_ENABLED` (true), `MODELS_DIR`, `ROADS_FILE` (the traffic map's OSM roads), `LOG_LEVEL`.

## Layout

- `app/consumer.py`: Kafka (`ride.events`, `driver.location`) into live features.
- `app/features.py`: the features in Redis (`ai:*`): requests per geohash-6 cell per 15 minutes, free drivers per cell, and the EWMA demand forecast.
- `app/model.py`: the two LightGBM regressors (wait minutes, surge) and their feature row.
- `app/engine.py`: the suggestion itself: ride type from history, best pickup time, a pickup spot nearby, route choice, reasons.
- `app/calendar.py`: Chennai time, holidays, the daily curve and the time-of-day traffic multiplier.
- `app/traffic.py`: the city's simulated traffic on the map's roads (same formula as the web app and the simulator), to time each route.
- `training/`:
  - `generate.py`: synthetic bookings with rush hours, off days, monsoon rain and busy areas;
  - `train.py`: training; writes `models/` and `models/metrics.json`.

The models learn a synthetic city, and traffic is the city's simulated traffic (free routing has no live traffic). Every suggestion shown is logged by ride-service (`ai_suggestion_log`), together with whether the rider booked with it, for retraining on real feedback.
