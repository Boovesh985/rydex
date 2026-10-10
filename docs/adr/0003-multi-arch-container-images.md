# 3. Multi-arch official container images; custom PostGIS image

Date: 2026-10-10 · Status: Accepted

## Context
Production runs on an Oracle Cloud Ampere A1 (arm64) VM; development runs on x86. Bitnami's free catalog was deprecated, so only official images are used. Each base image was checked on Docker Hub on 2026-10-10:

| Image | amd64 | arm64 |
|---|---|---|
| `postgres:16-bookworm` | ✅ | ✅ |
| `postgis/postgis:16-3.5` | ✅ | ❌ (amd64 only) |
| `redis:7-alpine` | ✅ | ✅ |
| `apache/kafka:4.3.1` | ✅ | ✅ |
| `eclipse-temurin:21-jre` | ✅ | ✅ |
| `python:3.12-slim` | ✅ | ✅ |
| `caddy:2` | ✅ | ✅ |

## Decision
- Build our own `rydex-postgres` image: `postgres:16-bookworm` + `postgresql-16-postgis-3` from the PGDG apt repo already configured in the official image. Built for `linux/amd64,linux/arm64` with buildx and published to GHCR.
- Every image we build is multi-arch; CI builds the PostGIS image for both platforms on every PR.
- Pin minor versions (`apache/kafka:4.3.1`) where upgrades can change behaviour; track major for Redis/Postgres patch releases.

## Consequences
- One extra image to maintain (a 5-line Dockerfile). PostGIS minor version follows PGDG (3.6 at time of writing).
- No dependency on a third-party community image.
