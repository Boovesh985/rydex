-- Driver profiles. user_id is the id of the DRIVER user in ride-service; there is no
-- cross-schema foreign key because each service owns its schema (ADR 0004).
CREATE TABLE drivers (
    user_id         uuid PRIMARY KEY,
    name            varchar(100) NOT NULL,
    vehicle_type    varchar(16)  NOT NULL CHECK (vehicle_type IN ('ECONOMY', 'COMFORT', 'XL')),
    vehicle_number  varchar(20)  NOT NULL,
    vehicle_model   varchar(60)  NOT NULL,
    rating          numeric(3,2) NOT NULL DEFAULT 5.00 CHECK (rating BETWEEN 1 AND 5),
    status          varchar(16)  NOT NULL DEFAULT 'OFFLINE' CHECK (status IN ('OFFLINE', 'AVAILABLE', 'ON_TRIP')),
    current_ride_id uuid,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    version         bigint       NOT NULL DEFAULT 0,
    CONSTRAINT ck_drivers_trip CHECK ((status = 'ON_TRIP') = (current_ride_id IS NOT NULL))
);

CREATE UNIQUE INDEX ux_drivers_vehicle_number ON drivers (upper(vehicle_number));
-- a driver can serve one ride at a time, and a ride has one driver
CREATE UNIQUE INDEX ux_drivers_current_ride ON drivers (current_ride_id) WHERE current_ride_id IS NOT NULL;
