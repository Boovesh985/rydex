# 11. Live tracking and notifications over WebSockets

Date: 2026-10-13 · Status: Accepted

## Context
Phase 4 adds the real-time half of Rydex (master prompt §4, §10):
- drivers stream GPS every 3 s;
- riders watch their driver approach with an ETA;
- riders and drivers get ride status notifications;
- offers reach drivers instantly instead of by polling.

tracking-service holds every WebSocket connection. Production runs one instance, but nothing may assume that (§10: "relay broadcasts via Redis pub/sub").

## Decision
- **Protocol: STOMP 1.2 over plain WebSocket at `/ws`.**
  - Spring's simple broker runs in memory in each instance.
  - There is no SockJS fallback: browsers, k6 and the simulator all speak WebSocket.
  - Heart-beats are 10 s each way.
  - The contract for clients is in [contracts/websocket.md](../../contracts/websocket.md).
- **Authentication on CONNECT.**
  - Browsers can't set headers on the WebSocket handshake, so the handshake itself is open. The access token travels in the STOMP CONNECT frame instead (`Authorization: Bearer …`) and is verified with the same RS256/JWKS decoder as REST (ADR 0006).
  - A `ChannelInterceptor` authorises every SUBSCRIBE and SEND. See the contract's table.
  - Once the token expires, the session can no longer subscribe or send, so the client reconnects with a fresh one. A long-lived socket never outlives a revoked user by more than 15 minutes.
  - A refused frame gets an ERROR frame naming the reason, and the connection closes, as STOMP specifies.
- **Who may watch a ride.**
  - The rider, the driver and admins may watch a ride's live location.
  - tracking-service answers this from its own projection of `ride.events` (`tracked_rides`). The projection is idempotent and ignores out-of-date events exactly as ride-service does (ADR 0009).
  - A rider who subscribes right after booking can be quicker than RIDE_REQUESTED. If the projection doesn't know the ride yet, tracking-service asks ride-service (`GET /api/rides/{id}` with the user's own token).
- **Fan-out through Redis pub/sub.**
  - Every push (location broadcast, notification, offer) is published as a `WsPush` on the Redis channel `ws:push`.
  - Every instance subscribes to that channel and hands the push to its local broker, so a rider on instance A sees what their driver sent to instance B.
  - Errors about a client's own frame are the exception: they go straight to the local broker, because that client is connected to this instance.
  - Delivery is fire-and-forget. A reconnecting client re-reads state over REST.
- **Offers are pushed by driver-service through the same channel**, as `OFFER` and `OFFER_CLOSED` messages for `/user/queue/offers`.
  - Offers are ephemeral (15 s) and already persisted in driver-service. Kafka would add latency and a durable record of something that expires before anyone could replay it.
  - The polling endpoint `GET /api/drivers/me/offer` remains for reconnecting clients.
  - Answers sent over the socket (`/app/offer.respond`) are forwarded to driver-service's REST API with the driver's own token. driver-service stays the only owner of matching.
- **Location ingest writes Redis exactly like driver-service.**
  - The presence code (Lua scripts and `PresenceStore`) moved to `libs/presence`, so both services update `driver:{id}:loc`, `driver:lastseen` and the GEO/supply indexes atomically and identically.
  - The rate limit is unchanged: one update per 500 ms per driver.
  - tracking-service has no driver table and so can't rebuild a lost availability projection. driver-service's new `PresenceReconciler` does it every 30 s for online drivers.
- **`driver.location` is telemetry.**
  - Each accepted update is sent straight to Kafka, keyed by driver, with acks=1 and no outbox.
  - At 3 s per online driver, an outbox row each would be most of the database's write load, and a lost update is superseded 3 s later.
  - A separate consumer group (`tracking-history`) stores the trip path in `driver_location_history`: at most one point per driver per 5 s, only while on a ride. The sampling check is part of the insert, so redelivery adds nothing.
- **Live ETA.**
  - OSRM `route` from the driver to the pickup (before arrival) or to the drop (during the trip).
  - The result is stored for 20 s and counted down in between, to spare the public demo server.
  - OSRM is never called on the broadcast path. When nothing is stored, the update carries the straight-line estimate (`etaEstimated: true`) and one background call per ride and phase fetches the route for the following updates. Measured locally, the first broadcast took 2.6 s with OSRM on the path; it is now immediate.

## Consequences
- **Two hops instead of one:** every push goes through Redis even with a single instance. That costs about a millisecond, and it keeps one code path for one or many instances.
- **No ordering guarantee within a session:** Spring's `setPreserveReceiveOrder` is not used, because its ordering decorator swallows interceptor exceptions, so refused frames would get no ERROR frame. Location updates are at least 500 ms apart, so they cannot overtake each other in practice.
- **Database load:** every location update looks up the driver's active ride in Postgres (an indexed query). This is the expected hotspot for the Phase 11 load test; caching the ride id in Redis is the planned fix.
- **REST still needed:** the REST location endpoint covers screens opened mid-ride. A missed notification is only recovered by reading the ride over REST.
- **REST heartbeats are not broadcast:** driver-service's `POST /api/drivers/me/location` still updates presence, but riders only see live positions sent over the WebSocket.
