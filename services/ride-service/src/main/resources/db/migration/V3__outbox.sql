-- Transactional outbox: rows are written in the same transaction as the state change
-- and relayed to Kafka by the outbox publisher (Phase 3).
CREATE TABLE outbox (
    id           uuid PRIMARY KEY,
    aggregate_id varchar(64)  NOT NULL,
    topic        varchar(100) NOT NULL,
    msg_key      varchar(100) NOT NULL,
    event_type   varchar(40)  NOT NULL,
    payload      jsonb        NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    published_at timestamptz
);

CREATE INDEX ix_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
