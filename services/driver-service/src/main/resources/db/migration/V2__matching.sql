-- One matching job per requested ride. The scheduler picks up jobs whose due_at has
-- passed: an offer that timed out, or a search to retry.
CREATE TABLE match_jobs (
    ride_id            uuid PRIMARY KEY,
    rider_id           uuid          NOT NULL,
    ride_type          varchar(16)   NOT NULL,
    pickup_lat         double precision NOT NULL,
    pickup_lng         double precision NOT NULL,
    pickup_address     varchar(300),
    drop_lat           double precision NOT NULL,
    drop_lng           double precision NOT NULL,
    drop_address       varchar(300),
    est_fare           numeric(10,2) NOT NULL,
    est_distance_m     integer       NOT NULL,
    est_duration_s     integer       NOT NULL,
    status             varchar(20)   NOT NULL
        CHECK (status IN ('SEARCHING', 'OFFERED', 'ASSIGNED', 'NO_DRIVER_FOUND', 'CANCELLED')),
    attempts           integer       NOT NULL DEFAULT 0,
    current_driver_id  uuid,
    assigned_driver_id uuid,
    due_at             timestamptz,
    search_deadline    timestamptz   NOT NULL,
    created_at         timestamptz   NOT NULL,
    updated_at         timestamptz   NOT NULL,
    version            bigint        NOT NULL DEFAULT 0
);

CREATE INDEX ix_match_jobs_due ON match_jobs (due_at) WHERE status IN ('SEARCHING', 'OFFERED');

-- Every offer made, kept for the admin view and acceptance-rate metrics.
CREATE TABLE match_offers (
    id           uuid PRIMARY KEY,
    ride_id      uuid        NOT NULL REFERENCES match_jobs (ride_id),
    driver_id    uuid        NOT NULL,
    attempt      integer     NOT NULL,
    eta_s        integer     NOT NULL,
    distance_m   integer     NOT NULL,
    offered_at   timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL,
    responded_at timestamptz,
    outcome      varchar(16) NOT NULL
        CHECK (outcome IN ('PENDING', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'CANCELLED'))
);

-- a ride is offered to a driver at most once
CREATE UNIQUE INDEX ux_match_offers_ride_driver ON match_offers (ride_id, driver_id);
CREATE INDEX ix_match_offers_driver_pending ON match_offers (driver_id) WHERE outcome = 'PENDING';
