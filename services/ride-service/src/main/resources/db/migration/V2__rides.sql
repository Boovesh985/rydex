-- Coordinates are stored as plain lat/lng (simple JPA mapping); the PostGIS geography
-- columns are generated from them for spatial queries (admin heatmap, analytics).
-- "drop" is a reserved word in SQL, hence pickup_*/drop_* prefixes.
CREATE TABLE rides (
    id               uuid PRIMARY KEY,
    rider_id         uuid          NOT NULL REFERENCES users (id),
    driver_id        uuid          REFERENCES users (id),
    ride_type        varchar(10)   NOT NULL CHECK (ride_type IN ('ECONOMY', 'COMFORT', 'XL')),
    status           varchar(20)   NOT NULL,
    pickup_lat       double precision NOT NULL,
    pickup_lng       double precision NOT NULL,
    drop_lat         double precision NOT NULL,
    drop_lng         double precision NOT NULL,
    pickup_point     public.geography(Point, 4326)
        GENERATED ALWAYS AS (public.ST_SetSRID(public.ST_MakePoint(pickup_lng, pickup_lat), 4326)::public.geography) STORED,
    drop_point       public.geography(Point, 4326)
        GENERATED ALWAYS AS (public.ST_SetSRID(public.ST_MakePoint(drop_lng, drop_lat), 4326)::public.geography) STORED,
    pickup_address   text,
    drop_address     text,
    est_fare         numeric(10, 2) NOT NULL,
    final_fare       numeric(10, 2),
    est_distance_m   integer       NOT NULL,
    est_duration_s   integer       NOT NULL,
    surge_multiplier numeric(3, 2) NOT NULL DEFAULT 1.00,
    otp_hash         varchar(64)      NOT NULL,
    suggestion_id    uuid,
    requested_at     timestamptz   NOT NULL,
    assigned_at      timestamptz,
    arrived_at       timestamptz,
    started_at       timestamptz,
    completed_at     timestamptz,
    cancelled_at     timestamptz,
    cancel_reason    text,
    cancelled_by     varchar(10),
    version          bigint        NOT NULL DEFAULT 0
);

CREATE INDEX ix_rides_rider ON rides (rider_id, requested_at DESC);
CREATE INDEX ix_rides_driver ON rides (driver_id, requested_at DESC) WHERE driver_id IS NOT NULL;
CREATE INDEX ix_rides_pickup_point ON rides USING gist (pickup_point);

-- A rider can have at most one ride in flight; enforced by the database, not just the service.
CREATE UNIQUE INDEX ux_rides_one_active_per_rider ON rides (rider_id)
    WHERE status IN ('REQUESTED', 'DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_PROGRESS');

CREATE TABLE ride_status_history (
    id          bigserial PRIMARY KEY,
    ride_id     uuid        NOT NULL REFERENCES rides (id) ON DELETE CASCADE,
    from_status varchar(20),
    to_status   varchar(20) NOT NULL,
    at          timestamptz NOT NULL,
    actor       varchar(64) NOT NULL
);

CREATE INDEX ix_ride_status_history_ride ON ride_status_history (ride_id, at);
