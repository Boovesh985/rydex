-- Phase 3 messaging (ADR 0009).
-- seq gives the relay a strict publish order (created_at can tie within one transaction).
ALTER TABLE outbox ADD COLUMN seq bigint GENERATED ALWAYS AS IDENTITY;
DROP INDEX ix_outbox_unpublished;
CREATE INDEX ix_outbox_unpublished ON outbox (seq) WHERE published_at IS NULL;
CREATE INDEX ix_outbox_published_at ON outbox (published_at) WHERE published_at IS NOT NULL;

-- Idempotent consumers: one row per (consumer, event) handled, written in the handler's transaction.
CREATE TABLE processed_events (
    consumer_name varchar(100) NOT NULL,
    event_id      uuid         NOT NULL,
    processed_at  timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_name, event_id)
);

CREATE INDEX ix_processed_events_at ON processed_events (processed_at);
