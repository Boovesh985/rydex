# MASTER PROMPT — "Rydex": Uber-style Ride Booking + Real-Time Tracking + AI Smart Ride Suggestions

> Paste this whole file into your AI coding agent (Claude Code, Cursor, etc.), or save it as `PROMPT.md` / `CLAUDE.md` at the repo root.
> Fill in the `<<PLACEHOLDERS>>` first.

---

## 0. Fill-ins

| Key | Value |
|---|---|
| App name | `Rydex` |
| GitHub repo | `<<github.com/USERNAME/rydex>>` |
| Backend domain (free DuckDNS) | `<<rydex.duckdns.org>>` |
| Vercel URL | `<<rydex.vercel.app>>` |
| Oracle Cloud home region | `<<ap-hyderabad-1 or ap-mumbai-1>>` (chosen at sign-up, cannot be changed; Always Free resources only exist in the home region) |
| Default map center | `<<lat, lng of your demo city>>` |

---

## 1. Role and working rules for the agent

You are a senior full-stack + platform engineer. Build a production-style, portfolio-grade Uber-like ride-hailing system end to end.

Rules:
1. **Work phase by phase** (Section 17). At the end of each phase: run the tests, show what was built, list how to verify it manually, then stop and wait for my "continue".
2. **Zero paid services.** Only open-source software and free tiers. The backend runs on **Oracle Cloud Always Free**; nothing may push usage beyond its free limits. If something would cost money, stop and propose a free alternative. Never add a paid API key requirement. (I also have ~$220 of AWS credits, kept only as a backup deployment target — see Section 13.)
3. Before using any library/image, check that its latest stable version is current and free to use. **Do not use Bitnami images** (their free catalog was deprecated); use official images.
4. No placeholder/TODO logic in core flows. Every feature must run locally with `docker compose up`.
5. Write clean, typed, tested code. Small commits with conventional commit messages.
6. Keep resource usage modest — production runs on a single Oracle Cloud Always Free ARM VM (2 OCPU, 12 GB RAM) (Section 13).
7. **All Docker images must be multi-arch (`linux/amd64` + `linux/arm64`)** — the production server is ARM (Ampere A1), while local dev may be x86.
8. Document decisions as short ADRs in `docs/adr/`.

---

## 2. Product scope (the 4-step flow)

1. **Rider** opens the app, sets pickup and drop on a map (search or tap).
2. **Ride booking**: sees ride types (Economy / Comfort / XL) with fare + ETA, plus an **AI Smart Ride Suggestion** card, then books.
3. **Driver**: the nearest available driver of that type receives the request (offer with 15 s countdown), accepts.
4. **Trip**: rider sees the driver moving live on the map with ETA ("Driver arriving · 2 min away"); driver verifies a 4-digit ride OTP, starts trip, completes it; payment is processed; both get notifications.

Roles: `RIDER`, `DRIVER`, `ADMIN`.

---

## 3. Tech stack (fixed — matches the reference architecture)

| Layer | Tech |
|---|---|
| Frontend | **React** + Vite + TypeScript, Tailwind CSS, React Router, TanStack Query, Zustand, **Leaflet / react-leaflet** with OpenStreetMap tiles, `@stomp/stompjs` |
| Backend | **Java 21 + Spring Boot** (latest stable), Spring Web, Spring Security (JWT), Spring Data JPA, Spring WebSocket (STOMP), Spring Kafka, Spring Data Redis, Flyway, springdoc-openapi, Resilience4j, Micrometer. Enable virtual threads. |
| Database | **PostgreSQL 16 + PostGIS** (persistent data: users, rides, payments) |
| Cache / hot geo data | **Redis 7** (GEO commands for live driver locations, locks, rate limits, caching, demand counters) |
| Event streaming | **Apache Kafka** (official `apache/kafka` image, KRaft mode, no ZooKeeper, single broker in prod) |
| Real-time | **WebSocket** (STOMP over WS, `wss://` in prod) |
| Geo search | **Geohashing** (precision 6 cells + 8 neighbours = 3×3 grid) + Redis `GEOSEARCH` |
| AI service | **Python 3.12 + FastAPI**, scikit-learn / LightGBM, pandas; consumes Kafka (aiokafka) and Redis |
| Maps/routing (free) | OSM tiles, **OSRM** public demo server for routes/ETA, **Nominatim** for geocoding — both proxied + cached through backend (respect 1 req/s Nominatim policy, add `User-Agent`) |
| Containers | Docker, Docker Compose |
| Frontend hosting | **Vercel** (Hobby plan) |
| Backend hosting | **Oracle Cloud Infrastructure (OCI) Always Free** — one Ampere A1 ARM VM (2 OCPU / 12 GB RAM), Docker Compose, Caddy reverse proxy with automatic Let's Encrypt HTTPS on a free DuckDNS domain. AWS EC2 is the documented backup target. |
| Errors | **Sentry** (free Developer plan) — React, Spring Boot, FastAPI |
| Load testing | **k6** (primary, supports HTTP + WebSocket); optional JMeter test plan for REST |
| Monitoring | **Prometheus + Grafana** (+ node-exporter, postgres-exporter, redis-exporter, kafka-exporter, cAdvisor) |
| CI/CD | GitHub Actions, images pushed to GitHub Container Registry (GHCR) |
| Testing | JUnit 5, Mockito, **Testcontainers** (Postgres, Redis, Kafka), pytest, Vitest + React Testing Library, Playwright (one happy-path E2E) |

---

## 4. Architecture

```
React (Vercel) ──HTTPS REST──► Caddy (OCI, TLS) ──► ride-service      ──┐
       │                                         ├─► driver-service    ──┤
       └──────────WSS (STOMP)───────────────────►└─► tracking-service  ──┤──► PostgreSQL+PostGIS
                                                     payment-service   ──┤──► Redis
                                                     ai-service (internal)┘
                         All services ◄──── Apache Kafka (events) ────►
          Prometheus scrapes all ─► Grafana     Sentry receives errors from all
```

### Services

| Service | Responsibility (maps to the reference image) |
|---|---|
| **ride-service** | Auth (issues RS256 JWT), users, rider APIs, fare estimate, ride booking, ride lifecycle state machine, Nominatim/OSRM proxy + cache, calls ai-service for suggestions. Produces `ride.events`. |
| **driver-service** ("Driver Service") | Driver profile & availability, **matching** (find nearby drivers via geohash/Redis GEO, send offers, timeout → next driver, assign). Consumes `ride.events` (`RIDE_REQUESTED`), produces `DRIVER_ASSIGNED` / `NO_DRIVER_FOUND`. |
| **tracking-service** ("Notification Service" + live tracking) | Holds all WebSocket connections. Ingests driver GPS, writes Redis GEO, publishes `driver.location`, broadcasts location to the rider of an active ride, pushes ride status notifications and driver offers. Consumes `ride.events`, `payment.events`. |
| **payment-service** ("Payment Service") | Consumes `RIDE_COMPLETED` → processes payment via a **mock provider** (default) or Razorpay **test mode** (free, optional), handles refunds on paid cancellations, produces `payment.events`. |
| **ai-service** | AI Smart Ride Suggestions (Section 9). Internal only (not exposed by Caddy). Consumes `ride.events` + `driver.location` to maintain demand/supply features. |

All services verify JWTs with the public key. Services never share database tables directly: each owns its schema (`ride`, `driver`, `payment` schemas in the same Postgres instance to save memory).

---

## 5. Data model (Flyway migrations, PostGIS)

- `users` (id uuid, name, email unique, phone, password_hash bcrypt, role, created_at)
- `drivers` (user_id pk/fk, vehicle_type ECONOMY|COMFORT|XL, vehicle_number, vehicle_model, rating numeric, status OFFLINE|AVAILABLE|ON_TRIP, updated_at)
- `rides` (id uuid, rider_id, driver_id null, ride_type, status, pickup geography(Point,4326), drop geography(Point,4326), pickup_address, drop_address, est_fare, final_fare, est_distance_m, est_duration_s, surge_multiplier, otp_hash, suggestion_id null, requested_at, assigned_at, arrived_at, started_at, completed_at, cancelled_at, cancel_reason, **version** (optimistic locking))
- `ride_status_history` (ride_id, from_status, to_status, at, actor)
- `driver_location_history` (driver_id, ride_id, point, speed, heading, recorded_at) — only stored during active trips, sampled every 5 s; index (ride_id, recorded_at)
- `payments` (id, ride_id unique, amount, currency INR, status PENDING|SUCCEEDED|FAILED|REFUNDED, provider, provider_ref, created_at)
- `outbox` (id, aggregate_id, topic, msg_key, payload jsonb, created_at, published_at) — **transactional outbox** per service
- `processed_events` (consumer_name, event_id, processed_at) — **idempotent consumers**
- `ai_suggestion_log` (id, user_id, request jsonb, response jsonb, accepted bool, created_at) — feedback for retraining

Ride state machine (enforce transitions server-side, reject illegal ones with 409):
`REQUESTED → DRIVER_ASSIGNED → DRIVER_ARRIVED → IN_PROGRESS → COMPLETED`
`REQUESTED | DRIVER_ASSIGNED | DRIVER_ARRIVED → CANCELLED`; `REQUESTED → NO_DRIVER_FOUND`.

Seed data: 1 admin, 5 riders, 50 drivers around the default map center.

---

## 6. Redis design

| Key | Type | Purpose |
|---|---|---|
| `geo:drivers:{rideType}` | GEO (sorted set) | Available drivers' positions (`GEOADD`, `GEOSEARCH ... BYRADIUS ... ASC COUNT`) |
| `driver:lastseen` | ZSET (score = epoch ms) | Heartbeats; a scheduled job removes drivers silent > 30 s from all GEO sets (GEO members don't expire on their own) |
| `driver:{id}:loc` | HASH, TTL 30 s | lat, lng, heading, speed, geohash, ts |
| `driver:{id}:lock` | `SET NX PX 20000` | Prevents double-assignment during offers |
| `ride:{id}:offer` | HASH, TTL 15 s | Current offered driver + attempt number |
| `demand:{geohash6}:{15minBucket}` | counter, TTL 2 h | Ride requests per cell (surge + AI features) |
| `supply:{geohash6}` | SET | Available drivers per cell |
| `cache:route:{hash}` / `cache:geo:{q}` | STRING, TTL 1 h / 24 h | OSRM / Nominatim response cache |
| `ratelimit:{userId}:{route}` | counter | Fixed-window rate limiting |
| `idem:{key}` | STRING, TTL 24 h | Idempotency-Key for `POST /rides` |

---

## 7. Geohash matching (the "Finding Nearby Drivers" flow)

1. Get rider pickup lat/lng.
2. Compute geohash (precision 6) and its 8 neighbours → 3×3 search area (use a small geohash library or implement the encoding).
3. Query `GEOSEARCH geo:drivers:{rideType} FROMLONLAT lng lat BYRADIUS 3 km ASC COUNT 10`. If empty, expand to 5 km, then 8 km.
4. Rank candidates by OSRM ETA to pickup (batch with OSRM `table` service), tie-break by rating.
5. Offer to driver #1 (acquire `driver:{id}:lock`), push via WebSocket, wait 15 s; on reject/timeout release lock and try the next. Max 5 attempts → `NO_DRIVER_FOUND`.
6. On accept: transactional update with optimistic locking, remove driver from GEO set, set status ON_TRIP, emit `DRIVER_ASSIGNED` via outbox.

Also expose `GET /api/admin/grid?lat&lng` returning the 3×3 cells with driver counts so the admin map can draw the geohash grid exactly like the reference diagram.

---

## 8. Kafka events (the "Asynchronous Event Processing" flow)

Topics (create on startup via `NewTopic` beans / init container):

| Topic | Key | Partitions | Retention | Producers → Consumers |
|---|---|---|---|---|
| `ride.events` | rideId | 6 | 7 days | ride-service, driver-service → driver, tracking, payment, ai |
| `driver.location` | driverId | 6 | 1 hour | tracking-service → ai-service, history writer |
| `payment.events` | rideId | 3 | 7 days | payment-service → ride-service, tracking-service |
| `*.DLT` | — | — | 7 days | dead-letter topics for poison messages |

Event envelope (JSON, documented as JSON Schema in `/contracts`):
```json
{ "eventId": "uuid", "eventType": "RIDE_REQUESTED", "version": 1,
  "occurredAt": "2026-01-12T10:45:00Z", "rideId": "RD12345",
  "payload": { "riderId": "U1001", "driverId": null, "rideType": "ECONOMY", "pickup": {...}, "drop": {...} } }
```
Event types: `RIDE_REQUESTED, DRIVER_ASSIGNED, NO_DRIVER_FOUND, DRIVER_ARRIVED, RIDE_STARTED, RIDE_COMPLETED, RIDE_CANCELLED, PAYMENT_INITIATED, PAYMENT_SUCCEEDED, PAYMENT_FAILED, PAYMENT_REFUNDED`.

Requirements: transactional outbox publisher (poll every 500 ms, mark published), consumer idempotency via `processed_events`, retries with backoff then DLT, separate consumer group per service, consumer lag visible in Grafana.

---

## 9. AI feature — Smart Ride Suggestions (free, no paid LLM)

Endpoint (internal): `POST /suggestions`
Input: `userId, pickup, drop, requestedAt`
Output (ride-service merges it into the estimate response):
```json
{
  "suggestionId": "uuid",
  "recommendedRideType": "COMFORT",
  "bestPickupTime": { "offsetMinutes": 10, "predictedWaitMin": 3, "predictedFare": 212, "savingsVsNow": 38 },
  "alternativePickup": { "lat": 0, "lng": 0, "walkMeters": 180, "predictedWaitMin": 2 },
  "route": { "choice": "alternative_1", "predictedDurationMin": 19 },
  "reasons": ["Demand in your area is high right now", "Waiting 10 min is predicted to cut the fare by ₹38"],
  "confidence": 0.74, "modelVersion": "2026-10-01"
}
```

Components:
1. **Demand/supply features** — ai-service consumes `ride.events` and `driver.location`; keeps per-geohash-cell request counts (15-min buckets) and available-driver counts in Redis; EWMA forecast of demand for the next 30 min.
2. **Wait-time + surge predictor** — LightGBM regressor. Features: hour, day of week, holiday flag, cell demand, cell supply, demand/supply ratio, ride type, distance, a time-of-day traffic multiplier. Evaluate candidate pickup times (now, +10, +20 min) and the neighbouring cells → pick the best trade-off.
3. **Ride-type preference** — from the user's ride history (frequency + price sensitivity); cold-start falls back to cheapest type with acceptable ETA.
4. **Route choice** — request OSRM with `alternatives=true`; score alternatives with the time-of-day traffic multiplier.
5. **Explanations** — template-based natural-language `reasons` (no LLM cost).

Training data: a synthetic data generator in `ai-service/training/` with realistic patterns (rush hours, weekends, rain-day randomness), optionally blended with the free public NYC TLC trip records. Script: `python -m training.train` → saves model to `models/` with version + metrics (MAE) in `models/metrics.json`. Add an endpoint `GET /model/info`.

Feedback loop: log every suggestion and whether the rider booked with it (`ai_suggestion_log`); show acceptance rate in Grafana.

Resilience: ride-service calls ai-service with a 300 ms timeout + Resilience4j circuit breaker; on failure, return estimates without the AI card (never block booking).

Be honest in docs: free routing has no live traffic, so traffic is modelled with time-of-day multipliers.

---

## 10. API and WebSocket contracts

REST (all under `/api`, OpenAPI at `/swagger-ui`, JSON errors as RFC 7807 Problem Details):
- `POST /auth/register`, `POST /auth/login`, `POST /auth/refresh`, `GET /me`
- `GET /places/search?q=` · `GET /places/reverse?lat&lng` (Nominatim proxy, cached, throttled)
- `POST /rides/estimate` → fares + ETAs per ride type + AI suggestion
- `POST /rides` (header `Idempotency-Key`) · `GET /rides/{id}` · `GET /rides?mine=true&page=` · `POST /rides/{id}/cancel`
- Driver: `PATCH /drivers/me/status` · `POST /rides/{id}/arrived` · `POST /rides/{id}/start` (body: otp) · `POST /rides/{id}/complete`
- `POST /ai/suggestions/{id}/feedback`
- Admin: `GET /admin/stats`, `GET /admin/drivers/live`, `GET /admin/grid`

Fare formula: `(base + perKm × km + perMin × min) × surge`, surge = clamp(1.0, 2.5, f(demand/supply in cell)).

WebSocket (STOMP, endpoint `/ws`, JWT in CONNECT headers, validated in a `ChannelInterceptor`; riders may only subscribe to their own ride topics):
- Driver → `/app/driver.location` `{lat,lng,heading,speed,ts}` every 3 s (client-side throttled; server rejects > 2 msg/s)
- Driver → `/app/offer.respond` `{rideId, accept}`
- Driver subscribes `/user/queue/offers`
- Rider subscribes `/topic/rides/{rideId}/location` and `/user/queue/notifications`
- Heartbeats 10 s; client auto-reconnect with exponential backoff and resubscribe.
- Design for horizontal scaling: relay broadcasts via Redis pub/sub so multiple tracking-service instances work (document it, even though prod runs one).

---

## 11. Frontend (React on Vercel)

Mobile-first, responsive, clean UI similar to the reference screens.

Pages:
- Login / Register (role select)
- **Rider home**: full-screen map, pickup ("Your location" via browser geolocation) and drop search with autocomplete, swap button, ride-type cards (Economy / Comfort / XL with price + ETA), **AI Smart Suggestion card** ("Shorter wait time / Better price / Smarter route" chips, reasons, "Use suggestion" button), Book Ride button.
- **Live ride**: animated driver marker (interpolate between updates, rotate by heading), route polyline, ETA banner, status timeline, OTP display, cancel, driver details card.
- **Driver dashboard**: Go Online toggle, incoming offer modal with 15 s countdown, navigation map to pickup/drop, Arrived / Start (OTP) / Complete buttons, earnings today.
- Ride history (rider and driver).
- **Admin**: live map of all drivers, geohash 3×3 grid overlay with counts, demand heatmap, KPIs.
- A dev-only "Simulate driver" toggle.

Env vars: `VITE_API_URL`, `VITE_WS_URL`, `VITE_SENTRY_DSN`. Use the Sentry Vite plugin to upload source maps in CI.

---

## 12. Driver simulator

`simulator/` (Python asyncio): spawns N fake drivers that log in, go online, accept offers with a probability, and drive along OSRM routes emitting GPS every 3 s. Used for demos, screenshots, and as a load-test aid. `python simulate.py --drivers 50 --city-center lat,lng`.

---

## 13. Docker, infra, deployment

### Containers
- Multi-stage Dockerfiles; Spring Boot layered jars on a JRE-only base; run as non-root; `HEALTHCHECK` on `/actuator/health`.
- Base images must publish `arm64` variants (Eclipse Temurin JRE, `postgis/postgis` or an arm64-compatible PostGIS image, `redis`, `apache/kafka`, `python:3.12-slim`, `caddy`, `prom/*`, `grafana/grafana`). Verify each one; if an image lacks arm64, pick an alternative and record it in an ADR.
- JVM flags: `-XX:MaxRAMPercentage=70 -XX:+UseSerialGC` with container `mem_limit` (~512 MB per Spring service).
- Kafka: `KAFKA_HEAP_OPTS=-Xmx1g -Xms1g`.
- Memory budget for the 12 GB VM (document it in `docs/runbook.md`): 4 Spring services ~2 GB, Kafka ~1.5 GB, Postgres ~1 GB, Redis ~256 MB, ai-service ~512 MB, Prometheus + Grafana + exporters ~1.5 GB, Caddy ~64 MB, leaving headroom for the OS. Add a 2 GB swap file as a safety net.
- `infra/docker-compose.yml` (local dev, all services + observability) and `infra/docker-compose.prod.yml` (images from GHCR, restart policies, memory limits, only Caddy exposes ports 80/443).

### Oracle Cloud Always Free (primary)
- Free limits as of mid-2026: Ampere A1 up to **2 OCPU and 12 GB RAM in total**. Oracle changed these limits in June 2026 without announcement — check the current Always Free page before provisioning and never exceed it.
- One `VM.Standard.A1.Flex` instance, 2 OCPU / 12 GB, Ubuntu LTS (aarch64), boot volume within the Always Free block-storage allowance. Note: ARM capacity in a region is often temporarily unavailable — document "retry creation later or at off-peak hours".
- Networking: VCN with a public subnet. **Open ports in two places**: the subnet's security list / NSG (22 from my IP only, 80, 443) **and** the host firewall (Oracle's Ubuntu images ship with iptables rules that block inbound traffic — add rules for 80/443 and persist them). Postgres/Redis/Kafka/Prometheus are **never** publicly exposed.
- Public IP → DuckDNS subdomain → Caddy auto-HTTPS. Use a reserved public IP if available within the free allowance; otherwise add a DuckDNS updater cron job. Caddy routes `/api/*` by prefix to services, `/ws` to tracking-service (WebSocket upgrade), `/grafana` to Grafana (login required).
- CORS: allow only the Vercel domain.
- **Stay on the Free Tier account (do not upgrade to Pay As You Go)** so nothing can ever be billed. Document the trade-off: Oracle may reclaim Always Free instances it considers idle (very low CPU, network and memory use over 7 days); this stack's steady memory use should keep it above that bar, but the runbook must show how to rebuild the server from scratch in under 30 minutes (cloud-init script + `docker compose up`).
- Harden SSH: key-only auth, no root login, `fail2ban`, unattended security upgrades.
- Backups: nightly `pg_dump` cron job, kept on the VM and copied to OCI Object Storage within the Always Free storage allowance.
- Write `infra/oci/cloud-init.yaml` that installs Docker, configures the firewall, swap and fail2ban, so a fresh VM is ready in one step.

### AWS EC2 (backup target only — do not deploy here unless I ask)
- Document in `docs/deploy-aws.md` how the same `docker-compose.prod.yml` runs on one EC2 instance (t4g.medium is ARM and reuses the arm64 images), with a security group (22 from my IP, 80, 443), Elastic IP, and an **AWS Budget alert** created before launching anything. This keeps my ~$220 of credits untouched by default.

### Vercel
- Import the repo, root `frontend/`, set env vars, SPA rewrite in `vercel.json`. Auto-deploy on push to `main`, preview deploys on PRs.

### CI/CD (GitHub Actions)
- `ci.yml` (PRs): Maven verify with Testcontainers, pytest, Vitest, lint, Docker build.
- `deploy.yml` (main): build **multi-arch (amd64 + arm64)** images with `docker buildx` and push to GHCR tagged with git SHA. Prefer GitHub's native ARM runners where free for the repo (public repos); otherwise use QEMU emulation and cache layers to keep build minutes low. Then SSH to the OCI VM → `docker compose pull && docker compose up -d` → smoke test `/actuator/health` → create Sentry release.
- Secrets in GitHub Secrets and an `.env` on the server; commit an `.env.example` only.

---

## 14. Observability

### Sentry
- Frontend: `@sentry/react` with error boundary, performance tracing (low sample rate to stay within free quota), source maps.
- Spring services: Sentry Spring Boot starter, environment + release tags, attach rideId/userId (no PII beyond IDs).
- FastAPI: `sentry-sdk[fastapi]`.
- Trigger a test error from each to prove it works.

### Prometheus + Grafana
- Actuator + Micrometer Prometheus registry on all Spring services; `prometheus-fastapi-instrumentator` on ai-service.
- Custom metrics: `rides_requested_total`, `rides_completed_total`, `rides_cancelled_total{reason}`, `matching_duration_seconds` (histogram), `matching_attempts`, `ws_active_sessions`, `location_updates_total`, `ai_suggestion_latency_seconds`, `ai_suggestion_accepted_total`, `payment_failures_total`.
- Exporters: node-exporter, cAdvisor, postgres-exporter, redis-exporter, kafka-exporter (consumer lag).
- Grafana dashboards **provisioned as code** (`infra/grafana/`): System, JVM, API latency/error rate (RED), Kafka lag, Rides business KPIs, WebSockets, AI feature, k6 results.
- Grafana alert rules (free contact point such as email or a Discord webhook): API p95 > 500 ms, error rate > 2%, Kafka lag > 1000, instance memory > 85%.
- Add a Grafana panel and alert for VM CPU and memory so idle-reclamation risk and memory pressure are both visible.
- If server memory is tight, document switching Prometheus/Grafana to Grafana Cloud's free tier via remote-write.

---

## 15. Load testing (k6, run from my laptop — not on the server)

`infra/k6/`:
- `booking_flow.js` — register/login → estimate → book → poll status (ramping VUs).
- `driver_locations_ws.js` — 500 simulated drivers sending WebSocket location updates every 3 s.
- `nearby_search.js` — stress the estimate/matching path.
- Thresholds: `http_req_duration p(95) < 400 ms`, `http_req_failed < 1%`, WS connect success > 99%.
- Stream results to Prometheus (k6 remote-write output) so they show up in Grafana next to server metrics.
- Optional `infra/jmeter/booking.jmx` for the REST flow.
- Write `docs/load-test-report.md`: setup, results table, Grafana screenshots, bottleneck found, fix applied, before/after numbers.

---

## 16. Testing requirements

- Unit tests for fare calculation, state machine, geohash neighbours, matching ranking.
- Integration tests with Testcontainers: booking → Kafka → driver assignment → WebSocket notification; outbox and idempotency; payment on completion.
- AI: tests for feature engineering and the fallback path; model metric check in CI (MAE below threshold).
- Frontend: component tests for booking and live-ride screens; one Playwright E2E happy path against docker compose.
- Target ≥ 70% line coverage on backend services.

---

## 17. Build phases (stop after each)

| # | Phase | Done when |
|---|---|---|
| 0 | Monorepo scaffold, Maven multi-module (`libs/common-events`, services), compose with Postgres/Redis/Kafka, CI skeleton | `docker compose up` brings infra up healthy; CI green |
| 1 | ride-service: auth, users, places/route proxy, estimate, booking, state machine, OpenAPI | Can register, estimate, book via Swagger |
| 2 | driver-service: availability, geohash + Redis GEO matching, offers, locking | Booking assigns nearest available driver in tests |
| 3 | Kafka: outbox, events, consumers, DLT, idempotency across services | Event flow integration test passes |
| 4 | tracking-service: WebSocket auth, location ingest, broadcasts, notifications | Rider receives live driver updates in test client |
| 5 | payment-service: mock + optional Razorpay test mode, refunds | Payment succeeds on completion; refund on paid cancel |
| 6 | Frontend: all pages, live map, driver dashboard, admin grid | Full ride works in browser with simulator |
| 7 | ai-service: data generator, training, `/suggestions`, Kafka features, UI card, feedback | Suggestion shown and logged; fallback works when service is down |
| 8 | Docker hardening + prod compose + Caddy | Full stack runs under memory limits locally |
| 9 | Sentry + Prometheus + Grafana dashboards + alerts | Test errors appear in Sentry; dashboards show data |
| 10 | Oracle Cloud Always Free VM (cloud-init) + DuckDNS + HTTPS + Vercel + CD pipeline; AWS backup guide written but not deployed | Public URL works end to end over HTTPS/WSS; rebuild-from-scratch runbook tested |
| 11 | k6 load tests + report + one performance fix | Report committed with before/after numbers |
| 12 | Docs | README with architecture diagram, demo GIF, setup guide, cost breakdown, ADRs, resume bullet points |

---

## 18. Repository layout

```
rydex/
├── frontend/                 # React + Vite (Vercel)
├── services/
│   ├── ride-service/
│   ├── driver-service/
│   ├── tracking-service/
│   └── payment-service/
├── libs/common-events/       # shared event DTOs, outbox, JWT utils
├── ai-service/               # FastAPI + LightGBM, training/
├── simulator/                # fake drivers
├── contracts/                # JSON Schemas, OpenAPI exports
├── infra/
│   ├── docker-compose.yml
│   ├── docker-compose.prod.yml
│   ├── caddy/Caddyfile
│   ├── prometheus/
│   ├── grafana/provisioning/
│   ├── oci/cloud-init.yaml
│   ├── k6/
│   └── jmeter/
├── .github/workflows/
└── docs/ (architecture.md, adr/, runbook.md, deploy-aws.md, load-test-report.md)
```

---

## 19. Final deliverables checklist

- [ ] Live frontend on Vercel, backend on `https://<<domain>>`, WebSockets over `wss://`
- [ ] One-command local setup: `docker compose -f infra/docker-compose.yml up`
- [ ] Simulator demo with 50 moving drivers
- [ ] AI suggestion card working with model metrics documented
- [ ] Sentry receiving errors from frontend + backend + AI service
- [ ] Grafana dashboards + alerts provisioned as code
- [ ] k6 report with numbers
- [ ] CI/CD green, multi-arch images in GHCR; README cost section showing ₹0/month on Oracle Always Free (and the AWS backup estimate)
- [ ] README with architecture diagram, screenshots/GIF, and 3–4 resume-ready bullet points with real metrics

Start with **Phase 0**. Ask me for any `<<PLACEHOLDER>>` you need before you begin.
