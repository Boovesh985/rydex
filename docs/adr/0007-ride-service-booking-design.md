# 7. Ride booking: upfront pricing, DB-enforced invariants, HMAC OTP

Date: 2026-10-10 · Status: Accepted

## Decision
- **Upfront pricing.** `fare = max(minimum, (base + perKm·km + perMin·min) · surge)`, rounded to whole rupees. It is recomputed on the server at booking; client prices are never trusted. `final_fare = est_fare` on completion.
- **Surge** = `clamp(1.0, 2.5, 1 + 0.25·(demand/supply − 1))` in steps of 0.1. Demand is counted over the 3×3 geohash-6 cells around the pickup for the current and previous 15-minute buckets. Supply comes from the `supply:{cell}` sets that driver-service maintains. With zero supply there is no signal, so no surge is applied.
- **Routing/geocoding** use OSRM and Nominatim (free OSM services), with Resilience4j circuit breakers and Redis caches (routes 1 h, places 24 h). Nominatim's 1 req/s policy is enforced cluster-wide with a Redis `SET NX PX` slot. If OSRM fails, a straight-line × 1.35 estimate at 22 km/h is used (`route.source = ESTIMATED`), so quoting never fails.
- **Invariants live in Postgres**:
  - At most one active ride per rider, via a partial unique index on `rides(rider_id)` for active statuses.
  - Optimistic locking (`version`) prevents two concurrent transitions, for example a rider cancelling while the driver marks arrival.
- **State machine** in `RideStatus.allowedNext()`. Illegal transitions return 409 with `from`/`to` in the problem body.
- **Ride OTP** = `HMAC-SHA256(secret, rideId) mod 10⁴`. The rider can be shown it at any time without storing it. The row keeps only `SHA-256(rideId:otp)`. After 5 wrong attempts the driver gets 429 for an hour.
- **Idempotency-Key** on `POST /rides`: the key is claimed with Redis `SET NX` (`PENDING`), then bound to the ride id for 24 h. A replay returns the original ride with `Idempotent-Replayed: true`. A key that is still in flight returns 409.
- **Coordinates** are stored as lat/lng columns. PostGIS `geography` columns are *generated* from them, which keeps JPA mapping trivial and leaves spatial queries available.
- **Outbox rows** are written in the same transaction as every state change. Phase 3 adds the relay to Kafka.
- **Rate limits**: a fixed 1-minute window in Redis, per user (per IP when anonymous) and per route group. If Redis is down, requests are allowed through.

## Consequences
- Free routing has no live traffic. ETAs are estimates, and the docs say so.
- The OTP secret must be stable and private in production (`OTP_SECRET`).
