# WebSocket API (tracking-service)

Real-time API for the rider and driver apps: STOMP 1.2 over a plain WebSocket. Design and trade-offs: [ADR 0011](../docs/adr/0011-live-tracking-websockets.md).

| | |
|---|---|
| Endpoint | `ws://localhost:8083/ws` locally, `wss://<backend>/ws` in production (no SockJS) |
| Auth | `Authorization: Bearer <access token>` header on the STOMP `CONNECT` frame (the same token as REST) |
| Heart-beats | server `10000,10000`; clients should send the same |
| Token expiry | the server refuses frames once the token has expired; reconnect with a fresh token (access tokens last 15 min) |
| Refused frames | the server sends an `ERROR` frame whose `message` header says why, then closes the connection |

Clients should reconnect with exponential backoff, resubscribe, and re-read state over REST (`GET /api/rides/{id}`, `GET /api/tracking/rides/{id}/location`, `GET /api/drivers/me/offer`): pushes sent while disconnected are not replayed.

## Who may do what

| Frame | Destination | Allowed |
|---|---|---|
| SUBSCRIBE | `/user/queue/notifications` | anyone connected |
| SUBSCRIBE | `/user/queue/errors` | anyone connected |
| SUBSCRIBE | `/user/queue/offers` | drivers |
| SUBSCRIBE | `/topic/rides/{rideId}/location` | the ride's rider, its driver, admins |
| SEND | `/app/driver.location` | drivers |
| SEND | `/app/offer.respond` | drivers |

## Driver → server

### `/app/driver.location`
Every ~3 s while online. More than 2 per second are dropped (an error is sent on `/user/queue/errors`).
```json
{ "lat": 13.0850, "lng": 80.2712, "heading": 180, "speed": 7.5, "ts": 1791894414966 }
```
`lat`/`lng` required; `heading` 0–360 degrees, `speed` m/s (0–100), `ts` device time (informational). Each accepted update:
- feeds matching (driver-service's Redis presence: position, heartbeat, GEO index if available);
- is broadcast to the rider while the driver is on a ride;
- is published on Kafka `driver.location`, and stored as the trip path (one point per 5 s).

### `/app/offer.respond`
```json
{ "rideId": "6b52c4f8-...", "accept": true }
```
Forwarded to driver-service (`POST /api/drivers/me/offers/{rideId}/accept|reject`); the result comes back on `/user/queue/offers` as `OFFER_RESPONSE`.

## Server → driver: `/user/queue/offers`
All messages have `type` and `rideId`.

`OFFER`: a ride offered to you (pushed by driver-service). `offer` has the same shape as `GET /api/drivers/me/offer`.
```json
{ "type": "OFFER", "rideId": "...", "reason": null,
  "offer": { "rideId": "...", "rideType": "ECONOMY", "pickup": {"lat":13.0827,"lng":80.2707,"address":"Chennai Central"},
             "drop": {...}, "estFare": 339.00, "estDistanceM": 22111, "estDurationS": 1359,
             "pickupEtaS": 85, "distanceToPickupM": 44, "attempt": 1,
             "offeredAt": "...", "expiresAt": "...", "secondsLeft": 15 } }
```
`OFFER_CLOSED`: the offer is no longer open; `reason` is `ACCEPTED`, `REJECTED`, `EXPIRED` or `CANCELLED` (the rider cancelled).
```json
{ "type": "OFFER_CLOSED", "rideId": "...", "reason": "EXPIRED", "offer": null }
```
`OFFER_RESPONSE`: the outcome of your `/app/offer.respond`. `status` is driver-service's HTTP status (e.g. 409 when the offer expired meanwhile); `assignment` is set on a successful accept.
```json
{ "type": "OFFER_RESPONSE", "rideId": "...", "accept": true, "ok": true, "status": 200, "detail": null,
  "assignment": { "rideId": "...", "driverId": "...", "riderId": "...", "pickup": {...}, "drop": {...}, "estFare": 339.00, "assignedAt": "..." } }
```

## Server → rider and driver: `/user/queue/notifications`
One per ride step, to the rider and (from assignment on) the driver where it concerns them.
```json
{ "eventId": "...", "type": "DRIVER_ARRIVED", "rideId": "...", "status": "DRIVER_ARRIVED",
  "title": "Your driver has arrived", "message": "Share your ride PIN with the driver to start the trip.",
  "driverId": "...", "fare": 339.00, "at": "2026-10-13T13:15:02Z" }
```
| `type` | rider | driver |
|---|---|---|
| `RIDE_REQUESTED` | Finding your driver | |
| `DRIVER_ASSIGNED` | Driver on the way | Ride assigned |
| `NO_DRIVER_FOUND` | No drivers available | |
| `DRIVER_ARRIVED` | Your driver has arrived | |
| `RIDE_STARTED` | Trip started | |
| `RIDE_COMPLETED` | Trip completed (with fare) | Trip completed |
| `RIDE_CANCELLED` | Ride cancelled (with reason) | Ride cancelled |

`eventId` identifies the ride event; a client may see the same one again after a reconnect and can drop it.

## Server → watchers: `/topic/rides/{rideId}/location`
The driver's position on each accepted update, with a live ETA.
```json
{ "rideId": "...", "driverId": "...", "lat": 13.0850, "lng": 80.2712, "heading": 180, "speed": 7.5,
  "phase": "TO_PICKUP", "etaS": 100, "distanceM": 555, "etaEstimated": false, "at": "..." }
```
- `phase`: `TO_PICKUP` (assigned), `AT_PICKUP` (arrived; `etaS` 0), `TO_DROP` (in progress).
- `etaS`/`distanceM` are to the pickup or the drop. They come from OSRM, refreshed every 20 s and counted down in between; `etaEstimated: true` means a straight-line estimate (the first update of a phase, or OSRM unavailable).
- `GET /api/tracking/rides/{rideId}/location` returns the same object (404 until the driver has reported).

## Server → anyone: `/user/queue/errors`
```json
{ "code": "RATE_LIMITED", "message": "At most 2 location updates per second; send one every 3 s", "at": "..." }
```
Codes: `RATE_LIMITED`, `INVALID_LOCATION`, `BAD_MESSAGE` (body is not valid JSON), `FAILED`.

## REST (tracking-service, `:8083`, bearer token)
- `GET /api/tracking/rides/{rideId}/location`: current position + ETA (rider, driver, admin; others 404)
- `GET /api/tracking/rides/{rideId}/path`: the trip path, one point every ~5 s, oldest first
