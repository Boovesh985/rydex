# Rydex — agent notes

The full specification is in [docs/MASTER_PROMPT.md](docs/MASTER_PROMPT.md). Read it before you change anything.

## Fill-ins (decided)
| Key | Value |
|---|---|
| GitHub repo | github.com/Boovesh985/rydex (GHCR: `ghcr.io/boovesh985/*`) |
| Default map center | Chennai — 13.0827, 80.2707 |
| Backend domain | TBD (DuckDNS, Phase 10) |
| Vercel URL | TBD (Phase 6/10) |
| OCI home region | TBD (Phase 10) |

## Working rules
- Work phase by phase (master prompt §17). After each phase: run the tests, summarise, list manual checks, then **stop and wait for "continue"**.
- Free tier only. Official, multi-arch images only (no Bitnami). Record decisions in `docs/adr/`.
- Conventional commits.

## Commands
- Build + tests: `./mvnw -B -ntp verify` (Windows: `.\mvnw.cmd`)
- Infra: `docker compose -f infra/docker-compose.yml up -d` (Postgres+PostGIS :5432, Redis :6379, Kafka :9092 — all on 127.0.0.1)

## Gotchas
- Integration tests reuse the local image `ghcr.io/boovesh985/rydex-postgres:16-3` (built by compose); they build it from `infra/postgres` only if missing. Containers are JVM singletons + `@DynamicPropertySource` — NOT `@ServiceConnection` (Spring 7 context pausing restarts container beans on new ports).
- Claude commits and pushes to origin itself. NEVER add Claude attribution (no Co-Authored-By, no "Generated with Claude Code"): the user must be the only contributor.
- Jackson 3 (`tools.jackson.*`): `JsonNode.asString()` not `asText()`.
- `@KafkaListener(id=...)` needs `idIsGroup = false`, otherwise the id replaces the consumer group.
- `mvnw spring-boot:run` inside a module resolves `libs/*` from `~/.m2`: run `./mvnw install -DskipTests -pl libs/common-events,libs/messaging` after changing them.
- Manual end-to-end check: `infra/scripts/smoke-test.ps1` (also run by the CI `e2e` job). It speaks STOMP itself (ClientWebSocket); in PowerShell, `[void]` every `.GetAwaiter().GetResult()` (Task<VoidTaskResult> leaks into function output) and don't reuse a caller's variable names inside helpers that run its script blocks.
- STOMP: do NOT `setPreserveReceiveOrder(true)` — its decorator swallows `StompAuthInterceptor` exceptions, so refused frames get no ERROR frame. Tests must let a SUBSCRIBE settle before publishing (StompTestClient does).
- Tests load Mockito as a `-javaagent` (root pom, via `maven-dependency-plugin:properties`); dynamic self-attach failed intermittently on Windows.
- A new service schema on an existing DB volume: re-run `infra/postgres/initdb/01-init.sh` (idempotent; see README).
- Running all three services plus `mvnw verify` can exhaust 8 GB RAM (forked test JVM fails to start): stop the services first.

## Layout
- `libs/common-events` — `EventEnvelope`, `EventType`, `Topics`
- `services/{ride,driver,tracking,payment}-service` — ports 8081/8082/8083/8084
- `libs/messaging` — `@EnableMessaging`: `OutboxWriter`, `OutboxRelay`, `ProcessedEvents`, `EventCodec`, DLT error handler (ADR 0009). Each service needs `processed_events`, and `outbox` unless `rydex.messaging.outbox.enabled=false` (tracking).
- `libs/presence` — `PresenceStore` + Lua scripts for `driver:{id}:state|loc`, `driver:lastseen`, GEO/supply indexes; used by driver-service and tracking-service (ADR 0011).
- tracking-service — STOMP at `/ws` (contract: `contracts/websocket.md`), `ws:push` Redis channel for fan-out (driver-service pushes offers there too), `tracked_rides` projection, `driver.location` producer + `tracking-history` consumer.
- Event flow: ride-service → RIDE_REQUESTED/CANCELLED/COMPLETED → driver-service (`events/RideLifecycleHandler`); driver-service → DRIVER_ASSIGNED/NO_DRIVER_FOUND → ride-service (`events/MatchingOutcomeHandler`); every ride event → tracking-service (`ride/RideProjection` → notifications).
- DB: database `rydex`, schema + role per service (`ride`/`ride_svc`, …). See ADR 0004.

## Progress
- [x] Phase 0 — scaffold, infra compose, CI skeleton
- [x] Phase 1 — ride-service: auth (RS256/JWKS, rotating refresh), places/route proxy, estimate, booking, state machine, outbox rows, OpenAPI
- [x] Phase 2 — driver-service: profiles, availability (Redis GEO + Lua), geohash/radius search, OSRM ETA ranking, 15 s offers with driver locks, leased matching scheduler, admin grid (ADR 0008)
- [x] Phase 3 — Kafka: libs/messaging outbox relay, idempotent consumers, retries + DLT; ride ⇄ driver event flow; CI e2e job (ADR 0009)
- [x] Post-Phase-3 audit fixes: ~5 min listener retries, single-active outbox relay, unmatched-ride sweeper + admin driver release (ADR 0010), matching radius/projection/lock fixes
- [x] Phase 4 — tracking-service: STOMP/WebSocket with JWT on CONNECT, location ingest (shared libs/presence), live ETA broadcasts, ride notifications, offer push + respond, Redis pub/sub fan-out, driver.location + trip history (ADR 0011)
