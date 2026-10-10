# 4. One Postgres instance, one schema and role per service

Date: 2026-10-10 · Status: Accepted

## Context
Services must not share tables, but a 12 GB VM cannot afford one Postgres per service.

## Decision
A single Postgres + PostGIS instance, database `rydex`, with schemas `ride`, `driver`, `payment`, `ai`. Each schema is owned by its own login role (`ride_svc`, …) whose `search_path` is `<schema>, public`. The roles are created by `infra/postgres/initdb/01-init.sh`. PostGIS lives in `public` so every service can use its types. Each service runs its own Flyway migrations against its own schema.

## Consequences
- Cross-schema access is denied by Postgres itself, not just by convention.
- Splitting a service into its own database later is a `pg_dump -n <schema>` away.
- One instance is still one failure domain. Accepted for a single-VM deployment.
