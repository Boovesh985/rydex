-- tracking-service schema (ADR 0011).

-- Projection of ride.events: who may watch a ride, and which ride a driver is on. Rebuilt from
-- events only; ride-service stays the source of truth.
CREATE TABLE tracked_rides (
    ride_id    uuid PRIMARY KEY,
    rider_id   uuid             NOT NULL,
    driver_id  uuid,
    ride_type  varchar(10),
    status     varchar(20)      NOT NULL,
    pickup_lat double precision NOT NULL,
    pickup_lng double precision NOT NULL,
    drop_lat   double precision NOT NULL,
    drop_lng   double precision NOT NULL,
    fare       numeric(10, 2),
    updated_at timestamptz      NOT NULL
);

-- every location update looks up the driver's active ride
CREATE INDEX ix_tracked_rides_active_driver ON tracked_rides (driver_id)
    WHERE status IN ('DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_PROGRESS');
CREATE INDEX ix_tracked_rides_rider ON tracked_rides (rider_id);

-- Driver positions during trips, one per driver every ~5 s (master prompt §5), written by the
-- history consumer of driver.location.
CREATE TABLE driver_location_history (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    driver_id   uuid             NOT NULL,
    ride_id     uuid             NOT NULL,
    lat         double precision NOT NULL,
    lng         double precision NOT NULL,
    point       public.geography(Point, 4326)
        GENERATED ALWAYS AS (public.ST_SetSRID(public.ST_MakePoint(lng, lat), 4326)::public.geography) STORED,
    speed       double precision,
    heading     double precision,
    recorded_at timestamptz      NOT NULL
);

-- (driver, time) is unique, so a redelivered event cannot add a second row
CREATE UNIQUE INDEX ux_location_history_driver_time ON driver_location_history (driver_id, recorded_at);
CREATE INDEX ix_location_history_ride ON driver_location_history (ride_id, recorded_at);

-- Idempotent consumers (ADR 0009). tracking-service has no outbox: it only publishes telemetry.
CREATE TABLE processed_events (
    consumer_name varchar(100) NOT NULL,
    event_id      uuid         NOT NULL,
    processed_at  timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_name, event_id)
);

CREATE INDEX ix_processed_events_at ON processed_events (processed_at);
