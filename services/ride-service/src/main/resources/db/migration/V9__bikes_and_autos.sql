-- ADR 0018: bike taxis, auto rickshaws and premium cabs alongside the cabs.
ALTER TABLE rides DROP CONSTRAINT rides_ride_type_check;
ALTER TABLE rides ADD CONSTRAINT rides_ride_type_check
    CHECK (ride_type IN ('BIKE', 'AUTO', 'ECONOMY', 'COMFORT', 'XL', 'PREMIUM'));
