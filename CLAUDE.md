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
- Frontend (`frontend/`): `npm run dev` (:5173, proxies /api + /ws), `npm run lint`, `npm run typecheck`, `npm test`, `npm run e2e` (Playwright, needs the 4 services)
- Simulator (`simulator/`): `python simulate.py --drivers 20 --speedup 3`; tests `python -m pytest`

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
- Running the services plus `mvnw verify` can exhaust 8 GB RAM (forked test JVM fails to start): stop the services first.
- Python helper scripts piped through a Bash heredoc lose backslashes (a doubled backslash arrives single): write them with the Write tool and raw strings.
- Frontend: one API origin routed by path prefix (ADR 0013); the Vite proxy table in `vite.config.ts` must match Caddy (Phase 8), the simulator's `LOCAL_ROUTES` and the smoke test. New npm/pip deps: pin a version at least two weeks old; TypeScript stays on 6.0 (typescript-eslint supports < 6.1).
- Frontend tests mock `api/endpoints`, `map/MapView` (Leaflet needs layout) and `realtime/hooks`. JSX test helpers must be `.tsx`.
- The in-app browser pane pauses requestAnimationFrame while hidden, and WebGL maps do not draw there at all: check maps with headless Playwright (`--enable-unsafe-swiftshader`, as in `playwright.config.ts`), not pane screenshots. Dev builds expose the map as `window.__rxMap`.
- Maps (ADR 0015): MapLibre's worker comes from `maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url` + `setWorkerUrl` (Vite pre-bundling loses it); the map container needs explicit `h-full w-full` (MapLibre's CSS makes it `relative`). A GeoJSON source added during a camera animation can stay stuck "loading": create sources with their data, keep 2D fits instant (`duration: 0`).
- Personal data (ADR 0014): `users.email`/`phone` are encrypted (`email_enc`/`phone_enc`, AES-GCM) and looked up by `email_hash` (HMAC). Never query users by email in SQL; the plain columns only exist so the SQL seed can hand accounts to `PiiProtector`, which encrypts them at startup. Passwords are Argon2id (`{argon2}`); seeded bcrypt hashes upgrade at sign-in. Test passwords need a letter and a digit.
- `RYDEX_STRICT_SECRETS=true` (deployments) makes ride-service refuse the dev OTP secret, dev PII keys and an ephemeral JWT key.
- One session per browser origin (localStorage): test rider and driver together with two browsers, or drive one side over the API.
- The simulator derives ride PINs from ride-service's dev `OTP_SECRET`; stop it before the smoke test (its cruising drivers can win the smoke test's offers).
- payment-service reads a git-ignored `services/payment-service/.env` (Razorpay test keys, `PAYMENT_PROVIDER`). Its tests set every payment property explicitly so a developer's `.env` cannot leak in; they run against `RazorpayStub`, never Razorpay. Only `rzp_test_` keys start the service.

## Layout
- `libs/common-events` — `EventEnvelope`, `EventType`, `Topics`
- `services/{ride,driver,tracking,payment}-service` — ports 8081/8082/8083/8084
- `libs/messaging` — `@EnableMessaging`: `OutboxWriter`, `OutboxRelay`, `ProcessedEvents`, `EventCodec`, DLT error handler (ADR 0009). Each service needs `processed_events`, and `outbox` unless `rydex.messaging.outbox.enabled=false` (tracking).
- `libs/presence` — `PresenceStore` + Lua scripts for `driver:{id}:state|loc`, `driver:lastseen`, GEO/supply indexes; used by driver-service and tracking-service (ADR 0011).
- tracking-service — STOMP at `/ws` (contract: `contracts/websocket.md`), `ws:push` Redis channel for fan-out (driver-service pushes offers there too), `tracked_rides` projection, `driver.location` producer + `tracking-history` consumer.
- frontend — React 19 + Vite 8 + Tailwind 4 + TanStack Query + Zustand + MapLibre GL + three.js + stompjs. `api/` (http client with cross-tab refresh, endpoints, types), `realtime/` (one STOMP connection, hooks), `map/` (`MapView` components, `theme` palettes/3D buildings/sky, `car3d` three.js custom layer, `useRealistic`), `pages/{rider,driver,admin}` + `TripPage` (`/trips/:id`, drivers and admins), `components/` (ui: Meter, Plate, PinTiles, Sheet, PasswordField; Realistic: HUD, speedometer, TripProgress). Playwright test in `e2e/`.
- simulator — Python asyncio fake drivers (`rydex_sim/`: own STOMP client, paced sign-ins, OSRM routes, PIN derivation).
- payment-service — one payment per ride (fare on RIDE_COMPLETED, fee on a RIDE_CANCELLED with `cancellationFee`); `PaymentProcessor` (leases, provider calls outside transactions) + `PaymentLedger` (state + outbox); providers `MockPaymentProvider` / `RazorpayProvider` (orders, checkout verify, polling, webhook, refunds); admin refunds; `/dev/checkout.html` (ADR 0012).
- Event flow: ride-service → RIDE_REQUESTED/CANCELLED/COMPLETED → driver-service (`events/RideLifecycleHandler`); driver-service → DRIVER_ASSIGNED/NO_DRIVER_FOUND → ride-service (`events/MatchingOutcomeHandler`); every ride event → tracking-service (`ride/RideProjection` → notifications); RIDE_COMPLETED/CANCELLED → payment-service (`events/RideChargeHandler`); payment.events → ride-service (`events/PaymentOutcomeHandler`, ride `paymentStatus`) and tracking-service (`payment/PaymentNotifications`).
- DB: database `rydex`, schema + role per service (`ride`/`ride_svc`, …). See ADR 0004.

## Progress
- [x] Phase 0 — scaffold, infra compose, CI skeleton
- [x] Phase 1 — ride-service: auth (RS256/JWKS, rotating refresh), places/route proxy, estimate, booking, state machine, outbox rows, OpenAPI
- [x] Phase 2 — driver-service: profiles, availability (Redis GEO + Lua), geohash/radius search, OSRM ETA ranking, 15 s offers with driver locks, leased matching scheduler, admin grid (ADR 0008)
- [x] Phase 3 — Kafka: libs/messaging outbox relay, idempotent consumers, retries + DLT; ride ⇄ driver event flow; CI e2e job (ADR 0009)
- [x] Post-Phase-3 audit fixes: ~5 min listener retries, single-active outbox relay, unmatched-ride sweeper + admin driver release (ADR 0010), matching radius/projection/lock fixes
- [x] Phase 4 — tracking-service: STOMP/WebSocket with JWT on CONNECT, location ingest (shared libs/presence), live ETA broadcasts, ride notifications, offer push + respond, Redis pub/sub fan-out, driver.location + trip history (ADR 0011)
- [x] Phase 5 — payment-service: mock provider + Razorpay test mode (orders, Checkout signature verify, polling, webhooks), late-cancellation fee, admin refunds (partial/full), payment.events → ride paymentStatus + notifications (ADR 0012)
- [x] Pre-Phase-7 audit (ADR 0014, 0015): encrypted email/phone + email HMAC index, Argon2id with bcrypt upgrade, account lockout, strict-secrets startup check, refresh-token cleanup, API docs toggle, cross-tab token refresh, CSP/security headers, driver cancel confirmation, trip pages + admin live rides; MapLibre vector maps and the realistic 3D tracking mode
- [x] Phase 6 — web app (rider booking + live ride + Razorpay Checkout, driver dashboard with offers/trip/simulated driving, admin map with geohash grid/demand/KPIs, history), driver simulator, driver card / route / admin stats endpoints, Vitest + Playwright, CI jobs (ADR 0013)
