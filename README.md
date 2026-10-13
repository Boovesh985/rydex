# Rydex

Uber-style ride hailing with live driver tracking and AI ride suggestions, built on free tiers only.
React · Java 21 / Spring Boot 4 · PostgreSQL + PostGIS · Redis · Apache Kafka · WebSockets · FastAPI.

> 🚧 Work in progress. Phases 0–4 (scaffold, ride-service, driver-service, Kafka event flow, live tracking over WebSockets) are done. The full plan is in [docs/MASTER_PROMPT.md](docs/MASTER_PROMPT.md).

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
```

Already have a database volume from before Phase 4? Add the new `tracking` schema and role once:
```powershell
Get-Content infra\postgres\initdb\01-init.sh -Raw | docker compose -f infra/docker-compose.yml exec -T postgres bash -s
```

**Check that it works.** With the infrastructure and the three services running, this tests everything built so
far and prints PASS/FAIL per check. It ends with a full ride across the services over Kafka (book, offer, accept,
arrive, OTP start, complete) and then the same ride live over WebSockets (the offer pushed to the driver, accepted
over the socket, notifications and the driver's position streamed to the rider). CI runs it on every push:
```powershell
powershell -ExecutionPolicy Bypass -File infra\scripts\smoke-test.ps1
```

**macOS / Linux / Git Bash**
```bash
docker compose -f infra/docker-compose.yml up -d
./mvnw verify
bash infra/scripts/gen-jwt-keys.sh
cd services/ride-service && ../../mvnw spring-boot:run
cd services/driver-service && ../../mvnw spring-boot:run   # second terminal
cd services/tracking-service && ../../mvnw spring-boot:run # third terminal
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
contracts/            JSON Schemas for Kafka events, the WebSocket (STOMP) API
infra/                docker compose, PostGIS image, Kafka topic script
docs/adr/             architecture decision records
```

## Decisions
See [docs/adr](docs/adr). Highlights: custom multi-arch PostGIS image (ADR 3), schema-per-service in one Postgres (ADR 4), single-broker KRaft Kafka (ADR 5), geohash matching (ADR 8), outbox + idempotent consumers (ADR 9), recovery from lost events (ADR 10), STOMP live tracking with Redis fan-out (ADR 11).
