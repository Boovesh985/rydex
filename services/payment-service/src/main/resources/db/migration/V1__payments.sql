-- payment-service schema (ADR 0012).

-- One payment per ride: the fare of a completed ride, or the fee of a late cancellation.
CREATE TABLE payments (
    id                  uuid PRIMARY KEY,
    ride_id             uuid          NOT NULL UNIQUE,
    rider_id            uuid          NOT NULL,
    driver_id           uuid,
    kind                varchar(20)   NOT NULL,
    amount              numeric(10, 2) NOT NULL CHECK (amount > 0),
    -- refunded: done; refunding: reserved by refunds still in flight
    refunded_amount     numeric(10, 2) NOT NULL DEFAULT 0,
    refunding_amount    numeric(10, 2) NOT NULL DEFAULT 0,
    currency            varchar(3)    NOT NULL,
    status              varchar(10)   NOT NULL,
    provider            varchar(10)   NOT NULL,
    -- mock: the charge id; Razorpay: the order id (order_...)
    provider_ref        varchar(64),
    -- Razorpay: the successful payment (pay_...), needed for refunds
    provider_payment_id varchar(64),
    failure_reason      varchar(300),
    -- processing: due when next_attempt_at has passed; lease_until stops two workers taking it at once
    attempts            int           NOT NULL DEFAULT 0,
    next_attempt_at     timestamptz,
    lease_until         timestamptz,
    created_at          timestamptz   NOT NULL,
    updated_at          timestamptz   NOT NULL,
    paid_at             timestamptz,
    version             bigint        NOT NULL,
    CHECK (refunded_amount + refunding_amount <= amount)
);

CREATE INDEX ix_payments_due ON payments (next_attempt_at) WHERE next_attempt_at IS NOT NULL;
CREATE INDEX ix_payments_rider ON payments (rider_id, created_at DESC);
CREATE INDEX ix_payments_status ON payments (status, created_at DESC);
CREATE UNIQUE INDEX ux_payments_provider_ref ON payments (provider, provider_ref) WHERE provider_ref IS NOT NULL;

CREATE TABLE refunds (
    id              uuid PRIMARY KEY,
    payment_id      uuid           NOT NULL REFERENCES payments (id),
    amount          numeric(10, 2) NOT NULL CHECK (amount > 0),
    status          varchar(10)    NOT NULL,
    reason          varchar(300)   NOT NULL,
    requested_by    uuid           NOT NULL,
    -- Razorpay: rfnd_...
    provider_ref    varchar(64),
    failure_reason  varchar(300),
    attempts        int            NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    lease_until     timestamptz,
    created_at      timestamptz    NOT NULL,
    processed_at    timestamptz,
    version         bigint         NOT NULL
);

CREATE INDEX ix_refunds_payment ON refunds (payment_id, created_at);
CREATE INDEX ix_refunds_due ON refunds (next_attempt_at) WHERE next_attempt_at IS NOT NULL;
CREATE UNIQUE INDEX ux_refunds_provider_ref ON refunds (provider_ref) WHERE provider_ref IS NOT NULL;

-- Transactional outbox and idempotent consumers (ADR 0009), as in ride-service.
CREATE TABLE outbox (
    id           uuid PRIMARY KEY,
    aggregate_id varchar(64)  NOT NULL,
    topic        varchar(100) NOT NULL,
    msg_key      varchar(100) NOT NULL,
    event_type   varchar(40)  NOT NULL,
    payload      jsonb        NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    published_at timestamptz,
    seq          bigint GENERATED ALWAYS AS IDENTITY
);

CREATE INDEX ix_outbox_unpublished ON outbox (seq) WHERE published_at IS NULL;
CREATE INDEX ix_outbox_published_at ON outbox (published_at) WHERE published_at IS NOT NULL;

CREATE TABLE processed_events (
    consumer_name varchar(100) NOT NULL,
    event_id      uuid         NOT NULL,
    processed_at  timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_name, event_id)
);

CREATE INDEX ix_processed_events_at ON processed_events (processed_at);
