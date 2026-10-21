# 23. Hardened containers, production compose and the Caddy edge

Date: 2026-10-21 · Status: Accepted (extends ADR 0003 and ADR 0013)

## Context
- Phase 8 of the plan: every component in a container, a production compose file, and Caddy in front, with the whole stack running under memory limits.
- The target is one 2-core, 12 GB arm64 VM (Oracle Always Free, Phase 10); development machines are x86 laptops with 8 GB.
- Until now only Postgres, Redis and Kafka ran in containers. The services ran from Maven or Python on the host.

## Decision
- **Images:** seven images (`ghcr.io/boovesh985/rydex-*`), each built for `linux/amd64` and `linux/arm64`.

  | Image | Base | Built from |
  |---|---|---|
  | ride-, driver-, tracking-, payment-service | `eclipse-temurin:21-jre-noble` | `services/Dockerfile` (`SERVICE`, `PORT`) |
  | ai-service | `python:3.12-slim-bookworm` (+ `libgomp1` for LightGBM) | `ai-service/Dockerfile` |
  | caddy (with the web app) | `caddy:2.11-alpine` | `infra/caddy/Dockerfile` |
  | simulator (the living city) | `python:3.12-slim-bookworm` | `simulator/Dockerfile` |

  Every base image was checked for both platforms on 2026-10-21 (`docker buildx imagetools inspect`), as were the `node:24-alpine` and `eclipse-temurin:21-jdk-noble` build stages.
- **One Spring Dockerfile.**
  - The Maven build runs on the build machine's own platform (`--platform=$BUILDPLATFORM`): jars are portable, and Maven under arm64 emulation would take many times longer.
  - Its stage does not depend on the service, so BuildKit builds the reactor once for all four images.
  - The jar is split into Spring Boot's layers (dependencies, loader, snapshot libraries, application), so a code change rebuilds only the last, small layer.
- **The JVM in a container:** `-XX:MaxRAMPercentage=55 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError` under a 768 MB limit. This gives a 422 MB heap, one GC thread, and a crash plus restart rather than a slow death when memory runs out.
  - The plan said 70% of about 512 MB. We measured that first: ride-service held 497 MB of its 512 and the kernel reclaimed memory 1,238 times in five minutes, one spike away from an OOM kill.
  - Native Memory Tracking showed why: about 285 MB lives outside the heap under load (metaspace and class space 156 MB, symbols 56 MB, code cache 35 MB, threads and the rest 38 MB). Next to a 358 MB heap, that does not fit in 512 MB.
  - With the new numbers, ten minutes of the living city peaked at 554 MB (ride-service), with no reclaim and no restarts.
- **Hardening:** every app container:
  - runs as uid 10001, never root;
  - has a read-only root filesystem, with a 64 MB in-memory `/tmp`;
  - drops every Linux capability (`cap_drop: ALL`) and sets `no-new-privileges`.

  Caddy binds ports 80 and 443 without root because Docker opens the low ports inside containers (`ip_unprivileged_port_start=0`). Health checks: the Spring images ask `/actuator/health`, ai-service asks `/health`, and Caddy asks its admin API (bound to 127.0.0.1 inside the container).
- **Caddy is the only public door** (`infra/caddy/Caddyfile`).
  - **Routes:**
    - `/api/*` goes to a service by path prefix, using the table in `frontend/src/edge.ts`, which the Vite proxy also uses. `edge.test.ts` fails if the Caddyfile drifts from it.
    - `/ws` goes to tracking-service (WebSocket upgrade).
    - Everything else is the web app, built into the image and served with the same headers as on Vercel. `upgrade-insecure-requests` and HSTS are sent over HTTPS only, so plain-HTTP localhost works.
  - **Never routed:** Actuator, Swagger, the JWKS and ai-service stay on the internal network.
  - **Limits:** request bodies are capped at 1 MB. A service that is down answers a Problem Details 502 within 5 seconds.
  - **Internal site:** a second site, `http://caddy`, gives containers (the simulator) the same routes without TLS.
  - **TLS:** `SITE_ADDRESS` is the public name; Caddy fetches and renews the certificate itself.
- **Two compose files.**
  - **`docker-compose.yml`:**
    - Plain `up` is still infra only, so running the services from an IDE keeps working.
    - `--profile app` adds the five services and Caddy (the web app on `http://localhost:8080`, each service still on its own 127.0.0.1 port for Swagger and the smoke test).
    - `--profile city` adds the simulator.
  - **`docker-compose.prod.yml`:**
    - Only GHCR images, restart policies, rotated logs (3 × 10 MB) and memory limits.
    - Only Caddy publishes ports.
    - Kafka gets one plain listener and the spec's 1 GB heap; Postgres is tuned for its 1 GB.
    - `RYDEX_STRICT_SECRETS=true`; API docs and the payment dev pages are off.
    - Every secret is required from `infra/.env`; compose refuses to start without it.
- **CI:**
  - `images`: builds each image for both platforms on every push.
  - `stack`: boots the full containerised stack under its limits and runs the smoke test, with the web app checks through Caddy. It then checks Caddy's headers and that no container was OOM-killed or restarted.

## Consequences
- **Same artefacts everywhere:** the images CI tests are the ones Phase 10 pushes and the VM pulls.
- **Memory:** limits add up to 6.6 GB in production (8.1 GB with Phase 9's monitoring), against 12 GB. Measured use is about 2.4 GB (docs/runbook.md), which also fits the 3.9 GB Docker gets on an 8 GB laptop.
- **Spring services:** 3 GB of limits instead of the planned 2 GB, the price of measured headroom.
- **JWT keys on Linux:** the key files must be readable by uid 10001 inside the container. The runbook does `chown 10001`; without the files, development falls back to an ephemeral key and production refuses to start.
- **The backend domain also serves the web app** (same origin, no CORS). Vercel stays the primary frontend.
