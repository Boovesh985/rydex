# 8. Driver presence and matching: Redis indexes, Postgres jobs, leased polling

Date: 2026-10-12 · Status: Accepted

## Context
driver-service has to find the nearest free driver of the right ride type within a few hundred milliseconds. It offers the ride for 15 s, moves on when a driver rejects or the offer times out, and must never assign one driver to two rides. Phase 3 delivers `RIDE_REQUESTED` and `RIDE_CANCELLED` over Kafka. Phase 4 moves location heartbeats and offer pushes to WebSockets. Everything runs on one small free-tier VM, but more than one instance must stay possible.

## Decision
- **Postgres is the source of truth** for driver status (`drivers`) and matching state (`match_jobs`, `match_offers`).
- **Redis holds the hot projection:**
  - `driver:{id}:state` stores the status, the ride type and the supply cell the driver is counted in.
  - `driver:{id}:loc` holds the last position (TTL 30 s).
  - `driver:lastseen` holds heartbeats.
  - `geo:drivers:{type}` and `supply:{geohash6}` are the search indexes.
- **Writes to several keys are single Lua scripts** (heartbeat, availability change, eviction). A heartbeat re-indexes a driver only if their projection says AVAILABLE. A status change and a concurrent heartbeat therefore cannot leave a stale GEO entry.
- **Silent drivers:** the reaper evicts drivers silent for more than 30 s from the indexes but keeps their status, so their next heartbeat makes them searchable again. If the projection is missing (e.g. Redis was flushed), the next heartbeat rebuilds it from Postgres.
- **Search:** `GEOSEARCH geo:drivers:{type} FROMLONLAT … BYRADIUS r km ASC COUNT n`, with r = 3, then 5, then 8 km until someone is found. Redis answers a radius query by scanning the geohash cell that covers the radius plus its 8 neighbours, which is the 3×3 search of the reference design. Our explicit geohash-6 grid (`common-events` `Geohash`) is used for supply and demand counting and for the admin grid view.
- **Ranking:**
  - Candidates are ranked by OSRM `table` ETA to the pickup, compared in whole minutes, then by rating, then by distance.
  - The OSRM call has a circuit breaker. On failure, ETA falls back to straight-line distance × 1.35 at 22 km/h, so matching never stops.
- **Offers:**
  - A driver is locked with `SET driver:{id}:lock <rideId> NX PX 20000` before an offer is written. Only the owner can release the lock (Lua compare-and-delete).
  - `match_offers` has a unique index on `(ride_id, driver_id)`, so a ride is never offered to the same driver twice.
  - The live offer is mirrored in `ride:{id}:offer` (TTL 15 s).
- **Scheduling:**
  - Every job has a `due_at`. A poller claims due jobs (new rides, expired offers, searches to retry) with `UPDATE … WHERE ride_id IN (SELECT … FOR UPDATE SKIP LOCKED) RETURNING`. The claim pushes `due_at` forward by a lease and bumps `version`.
  - Several instances can poll without blocking each other, and a crashed step is retried when its lease runs out.
  - Slow work (OSRM, Redis) runs outside database transactions.
- **Accept:**
  - Accepting is one transaction: close the offer, assign the job, set the driver to ON_TRIP and write `DRIVER_ASSIGNED` to the outbox.
  - Optimistic locking turns a race with offer expiry into a 409.
  - Redis is updated after commit.
- **Giving up:** `NO_DRIVER_FOUND` is emitted after 5 offers, or when nobody can be found for 90 s.
- **Phase 2 interface:**
  - `MatchingService.onRideRequested`, `onRideCancelled` and `onRideCompleted` are plain idempotent methods. Phase 3 calls them from Kafka consumers.
  - Drivers poll `GET /api/drivers/me/offer` until Phase 4 adds the WebSocket push.

## Consequences
- The Lua scripts build GEO and supply key names from prefixes at run time. That is fine on a single Redis node, but Redis Cluster would need hash tags.
- Driver status lives in two places. Redis is written after commit, so a Redis failure in that window leaves the projection stale until the driver's next status change or heartbeat. The heartbeat repairs a missing projection. A wrong one stays until the next status change.
- Polling every 500 ms adds up to half a second of latency to each matching step. Phase 3 can trigger a step directly from the Kafka consumer if that matters.
