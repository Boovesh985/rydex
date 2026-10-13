# 9. Event processing: outbox relay, idempotent consumers, retries then DLT

Date: 2026-10-13 · Status: Accepted

## Context
ride-service and driver-service react to each other's changes:
- **Booking:** RIDE_REQUESTED makes driver-service start matching.
- **Accepted offer:** DRIVER_ASSIGNED makes ride-service assign the driver.
- **Cancellation and completion:** they free the driver.

A state change and the message announcing it must not drift apart. A crash between "commit" and "send" would lose the event, and "send" before "commit" would announce something that never happened. Kafka delivers at least once, so every consumer will see some duplicates.

## Decision
- **Transactional outbox** in every producing service. The event row is inserted in the same transaction as the state change.
- **Relay** (`libs/messaging` `OutboxRelay`):
  - Polls every 500 ms and reads up to 100 unpublished rows in `seq` order (an identity column).
  - Sends them one at a time to the row's topic, keyed by ride id, and waits for the broker's ack. It stops at the first failure, so one ride's events are never published out of order.
  - Marks the sent rows `published_at`.
  - Only one relay per service runs a pass at a time. Each pass first takes `pg_try_advisory_xact_lock` on a key derived from the service's schema; a pass that can't get it does nothing. With several instances sharing rows (e.g. via `SKIP LOCKED`), one could publish a ride's second event while another still held its first. (Revised 2026-10-13 after an audit; the first version claimed concurrent relays kept the order, which was wrong.)
  - Delivery is at-least-once.
  - Published rows and processed-event markers are deleted after 14 days.
- **Producer settings:** `acks=all` and idempotence on. `max.block.ms` is set to 10 s, so a broker outage delays relaying instead of hanging threads.
- **Headers:** every record carries `eventId` and `eventType`. Consumers skip event types they don't handle without parsing the JSON.
- **Idempotent consumers:**
  - Each handler first inserts `(consumer_name, event_id)` into its service's `processed_events` (`ON CONFLICT DO NOTHING`), in the same transaction as its own changes.
  - A duplicate finds the marker and is skipped.
  - A failed handler rolls the marker back with everything else, so the retry runs.
- **Retries and dead letters:**
  - Each service consumes as its own group (`ride-service`, `driver-service`), one record per ack, 3 listener threads.
  - Failures are retried with exponential backoff: 1, 2, 4, 8, 16, 32 s, then every 60 s, for 10 retries (about 5 minutes). Then the record goes to `<topic>.DLT` on the same partition, with Spring Kafka's exception headers.
  - The first version retried for only 7 s, so a short database or Redis outage dead-lettered events and left rides or drivers stuck. Five minutes covers restarts and short outages. Each single wait stays under `max.poll.interval.ms`, so the consumer keeps its partitions while it waits. ADR 0010 covers what happens if even that is not enough.
  - Malformed events (unparseable JSON, missing ids or ride details) throw `NonRetryableEventException` and go to the DLT immediately.
- **Out-of-date outcomes are ignored, not failed:**
  - ride-service applies DRIVER_ASSIGNED or NO_DRIVER_FOUND only while the ride is still REQUESTED. If the rider cancelled first, the outcome is ignored.
  - driver-service frees the driver when it processes that same RIDE_CANCELLED. Both orders converge.
  - The status is checked up front instead of catching the state machine's exception. That exception would mark the transaction rollback-only and lose the marker.
- **Topics:** created by the `kafka-init` container in compose. `rydex.messaging.create-topics=true` declares the same topics as `NewTopic` beans, which tests use.
- **Shared code:** everything above lives in `libs/messaging` (`@EnableMessaging`), so the next producers and consumers (tracking, payment, ai) reuse it unchanged.

## Consequences
- Matching starts up to about 1 s after booking: up to 500 ms waiting for the outbox poll, the Kafka hop, then up to 500 ms waiting for the matching poll. Measured end to end locally: offer after about 1.1 s, and assignment visible to the rider about 0.6 s after accept.
- Sending one record at a time caps relay throughput at roughly one broker round trip per event per instance. That is ample here. Phase 11's load test will show whether batching per key is needed.
- While a record is being retried, its partition (and the other partition on the same listener thread) waits. That delays other rides' events by up to the retry time, but keeps each ride's events in order.
- The DLT has no automatic replay. An operator inspects the record and republishes it if needed. ADR 0010's safety nets make sure nobody stays stuck meanwhile.
