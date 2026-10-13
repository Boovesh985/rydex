# Rydex

Uber-style ride hailing with live driver tracking and AI ride suggestions, built on free tiers only.
React · Java 21 / Spring Boot 4 · PostgreSQL + PostGIS · Redis · Apache Kafka · WebSockets · FastAPI.

> 🚧 Work in progress. Phases 0–5 (scaffold, ride-service, driver-service, Kafka event flow, live tracking over WebSockets, payments) are done. The full plan is in [docs/MASTER_PROMPT.md](docs/MASTER_PROMPT.md).

## Quick start

Requires Docker and JDK 21. Maven is not required; use the wrapper.

**Windows (PowerShell)**
```powershell
# 1. Infrastructure: Postgres+PostGIS, Redis, Kafka (topics are created automatically)
docker compose -f infra/docker-compose.yml up -d

# 2. Build and test every backend module (integration tests use Testcontainers)
.\mvnw.cmd verify

# 3. Stable JWT signing key (optional; needs Git Bash). Without it an ephemeral key is used.
& "C:\Program Files\Git\bin\bash.exe" infra/scripts/gen-jwt-keys.sh

# 4. Run ride-service, then open http://localhost:8081/swagger-ui
cd services\ride-service; ..\..\mvnw.cmd spring-boot:run

# 5. In a second terminal, run driver-service (http://localhost:8082/swagger-ui)
cd services\driver-service; ..\..\mvnw.cmd spring-boot:run

# 6. In a third terminal, run tracking-service (WebSocket at ws://localhost:8083/ws)
cd services\tracking-service; ..\..\mvnw.cmd spring-boot:run

# 7. In a fourth terminal, run payment-service (http://localhost:8084/swagger-ui)
cd services\payment-service; ..\..\mvnw.cmd spring-boot:run
```

Payments use a mock provider by default (every charge succeeds at once). To use **Razorpay test mode** instead,
copy `services/payment-service/.env.example` to `services/payment-service/.env` (git-ignored), fill in your test
keys and restart payment-service. No real money moves. The rider pays in Razorpay Checkout; until the frontend
exists, `http://localhost:8084/dev/checkout.html` does that for one ride (UPI ID `success@razorpay`). See ADR 12.

Already have a database volume from before Phase 4? Add the newer schemas and roles once (safe to repeat):
```powershell
Get-Content infra\postgres\initdb\01-init.sh -Raw | docker compose -f infra/docker-compose.yml exec -T postgres bash -s
```

**Check that it works.** With the infrastructure and the four services running, this tests everything built so
far and prints PASS/FAIL per check. It runs a full ride across the services over Kafka (book, offer, accept,
arrive, OTP start, complete), the same ride live over WebSockets (the offer pushed to the driver, accepted over
the socket, notifications and the driver's position streamed to the rider), and payments (the fare charged, a late
cancellation fee, an admin refund). CI runs it on every push:
```powershell
powershell -ExecutionPolicy Bypass -File infra\scripts\smoke-test.ps1
```
In Razorpay test mode, add `-PayInBrowser`: it opens the test checkout to pay the cancellation fee, then refunds it.

**macOS / Linux / Git Bash**
```bash
docker compose -f infra/docker-compose.yml up -d
./mvnw verify
bash infra/scripts/gen-jwt-keys.sh
cd services/ride-service && ../../mvnw spring-boot:run
cd services/driver-service && ../../mvnw spring-boot:run   # second terminal
cd services/tracking-service && ../../mvnw spring-boot:run # third terminal
cd services/payment-service && ../../mvnw spring-boot:run  # fourth terminal
```

Demo accounts (seeded): `admin@rydex.dev`, `rider1..5@rydex.dev`, `driver01..50@rydex.dev`, all with password `Rydex@123`.
Drivers 01–30 drive ECONOMY, 31–42 COMFORT and 43–50 XL. All of them start offline.

Every service trusts the same token, so sign in once on ride-service (`POST /api/auth/login`) and paste the
`accessToken` into **Authorize** on any Swagger page.

| Component | Address (local) |
|---|---|
| PostgreSQL 16 + PostGIS | `localhost:5432` db `rydex` |
| Redis 7 | `localhost:6379` |
| Kafka 4.3 (KRaft) | `localhost:9092` (host), `kafka:29092` (containers) |
| ride / driver / tracking / payment service | `:8081` / `:8082` / `:8083` / `:8084` |

## Repository layout

```
libs/common-events/   shared event envelope, event types, topic names, geohash, Redis keys
libs/messaging/       outbox relay, idempotent consumers, retries + dead-letter topics (@EnableMessaging)
libs/presence/        driver presence in Redis (Lua scripts), shared by driver- and tracking-service
services/             ride, driver, tracking, payment (Spring Boot)
contracts/            JSON Schemas for Kafka events (ride, payment, driver location), the WebSocket (STOMP) API
infra/                docker compose, PostGIS image, Kafka topic script
docs/adr/             architecture decision records
```

## Decisions
See [docs/adr](docs/adr). Highlights: custom multi-arch PostGIS image (ADR 3), schema-per-service in one Postgres (ADR 4), single-broker KRaft Kafka (ADR 5), geohash matching (ADR 8), outbox + idempotent consumers (ADR 9), recovery from lost events (ADR 10), STOMP live tracking with Redis fan-out (ADR 11), payments with Razorpay test mode and refunds (ADR 12).
