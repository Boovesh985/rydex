# 5. Kafka in KRaft mode, single broker, topics created explicitly

Date: 2026-10-10 · Status: Accepted

## Context
Kafka 4.x has removed ZooKeeper. The VM can spare about 1.5 GB for Kafka.

## Decision
- `apache/kafka:4.3.1` in combined broker+controller KRaft mode, one node, heap 1 GB.
- Two listeners: `INTERNAL` (`kafka:29092`, container-to-container) and `EXTERNAL` (`localhost:9092`, apps on the host during development).
- `auto.create.topics.enable=false`. The one-shot `kafka-init` container runs `infra/kafka/create-topics.sh`, which is idempotent (`--if-not-exists`).
- Topics: `ride.events` (6p, 7d), `driver.location` (6p, 1h), `payment.events` (3p, 7d) and a `.DLT` topic for each with the **same partition count**, because Spring's `DeadLetterPublishingRecoverer` keeps the source partition number by default.
- Replication factor 1 (single broker).

## Consequences
- No redundancy: a broker restart pauses event flow. The transactional outbox (Phase 3) keeps producers safe across restarts.
- Topic names live in `com.rydex.events.Topics` and the script; both must change together.
