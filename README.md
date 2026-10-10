# Rydex

Uber-style ride hailing with live driver tracking and AI ride suggestions, built on free tiers only.
React · Java 21 / Spring Boot 4 · PostgreSQL + PostGIS · Redis · Apache Kafka · WebSockets · FastAPI.

> 🚧 Work in progress. Phases 0–1 (scaffold, ride-service) are done. The full plan is in [docs/MASTER_PROMPT.md](docs/MASTER_PROMPT.md).

## Quick start

Requires Docker and JDK 21. Maven is not required; use the wrapper.

```bash
# 1. Infrastructure: Postgres+PostGIS, Redis, Kafka (topics are created automatically)
docker compose -f infra/docker-compose.yml up -d

# 2. Build and test every backend module (integration tests use Testcontainers)
./mvnw verify

# 3. (optional) stable JWT signing key, otherwise an ephemeral one is used
bash infra/scripts/gen-jwt-keys.sh

# 4. Run ride-service and open Swagger UI at http://localhost:8081/swagger-ui
cd services/ride-service && ../../mvnw spring-boot:run
```

Demo accounts (seeded): `admin@rydex.dev`, `rider1..5@rydex.dev`, `driver01..50@rydex.dev`, all with password `Rydex@123`.

| Component | Address (local) |
|---|---|
| PostgreSQL 16 + PostGIS | `localhost:5432` db `rydex` |
| Redis 7 | `localhost:6379` |
| Kafka 4.3 (KRaft) | `localhost:9092` (host), `kafka:29092` (containers) |
| ride / driver / tracking / payment service | `:8081` / `:8082` / `:8083` / `:8084` |

## Repository layout

```
libs/common-events/   shared event envelope, event types, topic names
services/             ride, driver, tracking, payment (Spring Boot)
contracts/            JSON Schemas for Kafka events
infra/                docker compose, PostGIS image, Kafka topic script
docs/adr/             architecture decision records
```

## Decisions
See [docs/adr](docs/adr). Highlights: custom multi-arch PostGIS image (ADR 3), schema-per-service in one Postgres (ADR 4), single-broker KRaft Kafka (ADR 5).
