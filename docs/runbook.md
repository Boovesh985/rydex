# Rydex runbook

How to run the containerised stack, what it costs in memory, and what to do when something goes wrong. Phase 10 adds the Oracle Cloud server itself (cloud-init, DuckDNS, backups, rebuilding from scratch).

- [The stack in containers](#the-stack-in-containers)
- [Memory budget](#memory-budget)
- [Production compose](#production-compose)
- [Day to day](#day-to-day)
- [When something breaks](#when-something-breaks)

## The stack in containers

Every component has its own image, built for amd64 and arm64 (ADR 0023):

| Image | What | Port (internal) |
|---|---|---|
| `rydex-ride-service`, `-driver-service`, `-tracking-service`, `-payment-service` | the Spring services | 8081-8084 |
| `rydex-ai-service` | smart suggestions | 8085 |
| `rydex-caddy` | the edge: HTTPS, routing, and the web app | 80, 443 |
| `rydex-simulator` | the living city (optional) | none |
| `rydex-postgres` | Postgres 16 + PostGIS | 5432 |

Locally, build and start everything, then open `http://localhost:8080`:

```bash
docker compose -f infra/docker-compose.yml --profile app up -d --build
```

Add `--profile city` for the simulated drivers and riders. Each service still answers on its own `127.0.0.1` port (Swagger, the smoke test), and `npm run dev` on :5173 works against the containers too. Stop with `docker compose -f infra/docker-compose.yml --profile app --profile city down` (add `-v` to wipe the data).

The smoke test runs against the containers as it does against services on the host; `-FrontendUrl` sends its web app checks through Caddy:

```powershell
powershell -ExecutionPolicy Bypass -File infra\scripts\smoke-test.ps1 -FrontendUrl http://localhost:8080
```

## Memory budget

Limits per container (`mem_limit`) and what each uses. Measured with `docker stats` on 2026-10-21, after the smoke test and ten minutes of the living city (40 drivers, 30 riders), locally:

| Container | Limit (production) | Limit (local) | Uses |
|---|---|---|---|
| ride-service | 768 MB | 768 MB | 554 MB |
| driver-service | 768 MB | 768 MB | 512 MB |
| tracking-service | 768 MB | 768 MB | 519 MB |
| payment-service | 768 MB | 768 MB | 358 MB |
| ai-service | 512 MB | 512 MB | 45-95 MB |
| Kafka | 1.5 GB (1 GB heap) | 1 GB (512 MB heap) | 290-370 MB |
| Postgres | 1 GB | 1 GB | 120 MB |
| Redis | 256 MB (200 MB data) | 256 MB | 7 MB |
| Caddy | 128 MB | 128 MB | 35 MB |
| simulator (optional) | 256 MB | 256 MB | 37 MB |
| **Total** | **6.6 GB** | **6.1 GB** | **about 2.4 GB** |

- **Production (12 GB VM):** Phase 9 adds Prometheus, Grafana and exporters (about 1.5 GB) to the 6.6 GB of limits. That leaves almost 4 GB for the OS, the page cache Postgres and Kafka rely on, and headroom. Add a 2 GB swap file as a safety net (cloud-init does, Phase 10).
- **The JVMs:** `MaxRAMPercentage=55` of 768 MB gives each a 422 MB heap.
  - What a JVM keeps outside the heap was measured with Native Memory Tracking at about 285 MB under load: metaspace and class space 156 MB, symbols 56 MB, code cache 35 MB, threads and the rest about 38 MB.
  - The plan's 512 MB with a 70% heap would need about 640 MB at worst: ride-service sat at its limit, and the kernel had to reclaim memory 1,238 times in five minutes.
  - At 768 MB the worst case is about 710 MB.
  - A heap that runs out kills the JVM (`ExitOnOutOfMemoryError`) and Docker restarts it, so a leak shows up as restarts, not a slow crawl.
- **An 8 GB laptop:** Docker Desktop gives its VM about half the RAM (3.9 GB here); the limits add up to more than that, but what the stack actually uses (about 2.4 GB) fits. Close the services running on the host first (the containers replace them).

## Production compose

`infra/docker-compose.prod.yml` runs the images from GHCR. Only Caddy publishes ports (80, 443 and 443/udp for HTTP/3); Postgres, Redis, Kafka and the services are on the internal network only.

1. **Settings:** `cp infra/.env.example infra/.env`, fill in every value, `chmod 600 infra/.env`. Compose refuses to start while a required one is empty.
2. **JWT keys:** `bash infra/scripts/gen-jwt-keys.sh`, then let the container user read them:

   ```bash
   sudo chown 10001:10001 infra/keys/jwt-private.pem infra/keys/jwt-public.pem
   ```

   ride-service refuses to start without them (`RYDEX_STRICT_SECRETS=true`), as it does with any development secret.
3. **Start:**

   ```bash
   docker compose -f infra/docker-compose.prod.yml pull
   docker compose -f infra/docker-compose.prod.yml up -d --wait
   ```

   Add `--profile city` to keep the demo city busy (which also keeps the VM above Oracle's idle threshold).
4. **Check:** `curl -I https://$SITE_ADDRESS/` returns 200 with the security headers, and `docker compose -f infra/docker-compose.prod.yml ps` shows every container healthy.

What the production file changes, compared with local:

- `RYDEX_STRICT_SECRETS=true`;
- Swagger and the payment dev pages are off;
- `restart: unless-stopped` everywhere;
- logs rotate at 3 × 10 MB per container;
- Kafka has one internal listener and a 1 GB heap;
- Postgres is tuned for its 1 GB (`shared_buffers=256MB`, 80 connections).

**Back up `infra/.env` (above all `PII_DATA_KEY`) and `infra/keys/` with the database.** Without the data key the stored emails and phone numbers cannot be read.

## Day to day

| Task | Command (add `-f infra/docker-compose.prod.yml` on the server) |
|---|---|
| Status and health | `docker compose ps` |
| Memory and CPU | `docker stats --no-stream` |
| Logs of one service | `docker compose logs -f --tail 100 ride-service` |
| Deploy a new version | set `RYDEX_TAG` in `infra/.env`, then `docker compose pull && docker compose up -d --wait` |
| Roll back | set the previous `RYDEX_TAG`, then the same two commands |
| Restart one service | `docker compose restart driver-service` |
| A database shell | `docker compose exec postgres psql -U rydex -d rydex` |

## When something breaks

- **A container keeps restarting:** run `docker inspect -f '{{.State.OOMKilled}} {{.RestartCount}}' rydex-ride-service-1`.
  - `true`: it hit its memory limit. Raise `mem_limit` (and keep the budget above honest).
  - Otherwise, read `docker compose logs`.
- **ride-service exits at start with "development secret" or "no JWT signing key file":** a value in `infra/.env` is missing or still a development default, or the key files are not readable by uid 10001 (step 2 above).
- **The site answers 502 with "The service behind this path is not answering":** that service is down or starting. Check `docker compose ps` and its logs.
- **No HTTPS certificate:** check that ports 80 and 443 are open in the cloud firewall and in the VM's own firewall, and that `SITE_ADDRESS` resolves to the VM. Then read `docker compose logs caddy`.
- **Kafka:** run `docker compose logs kafka | grep -i fenced`. A broker fenced again and again was short of CPU or memory (CLAUDE.md, Kafka session timeout).
