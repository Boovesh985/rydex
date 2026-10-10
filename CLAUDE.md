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

## Layout
- `libs/common-events` — `EventEnvelope`, `EventType`, `Topics`
- `services/{ride,driver,tracking,payment}-service` — ports 8081/8082/8083/8084
- DB: database `rydex`, schema + role per service (`ride`/`ride_svc`, …). See ADR 0004.

## Progress
- [x] Phase 0 — scaffold, infra compose, CI skeleton
- [x] Phase 1 — ride-service: auth (RS256/JWKS, rotating refresh), places/route proxy, estimate, booking, state machine, outbox rows, OpenAPI
