# 2. Monorepo with a Maven multi-module backend

Date: 2026-10-10 · Status: Accepted

## Context
Four Spring Boot services share event contracts and JWT handling. One developer, one CI pipeline, free GitHub Actions minutes.

## Decision
- One Git repo: `frontend/`, `services/*`, `libs/common-events`, `ai-service/`, `simulator/`, `infra/`.
- Root `pom.xml` inherits `spring-boot-starter-parent` 4.1.1 and lists every JVM module, so versions are managed in one place.
- `libs/common-events` holds only the event envelope, event types and topic names. No business logic is shared: services talk through Kafka and REST only.
- Maven Wrapper (`only-script`, Maven 3.9.16) so nobody needs Maven installed.
- Java 21 (LTS) with virtual threads enabled in every service.

## Consequences
- Atomic changes across a contract and its producers/consumers.
- A change in `common-events` rebuilds every service. Fine at this size.
- Services still deploy as separate images and own separate DB schemas (ADR 0004).
