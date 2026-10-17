-- ADR 0018: bike taxis, auto rickshaws and premium cabs alongside the cabs.
ALTER TABLE drivers DROP CONSTRAINT drivers_vehicle_type_check;
ALTER TABLE drivers ADD CONSTRAINT drivers_vehicle_type_check
    CHECK (vehicle_type IN ('BIKE', 'AUTO', 'ECONOMY', 'COMFORT', 'XL', 'PREMIUM'));

-- An existing database: the NPC fleet (drivers 02-50) gets the new mix, the same as
-- R__seed_demo_drivers gives a fresh one. driver01 keeps whatever its owner chose.
UPDATE drivers d
SET vehicle_type  = v.vehicle_type,
    vehicle_model = v.vehicle_model,
    updated_at    = now()
FROM (SELECT ('00000000-0000-4000-8000-' || lpad((200 + g)::text, 12, '0'))::uuid AS user_id,
             CASE WHEN g <= 12 THEN 'ECONOMY' WHEN g <= 19 THEN 'COMFORT' WHEN g <= 23 THEN 'XL'
                  WHEN g <= 26 THEN 'PREMIUM' WHEN g <= 39 THEN 'BIKE' ELSE 'AUTO' END AS vehicle_type,
             CASE
                 WHEN g <= 12 THEN (ARRAY['Maruti Suzuki Swift', 'Hyundai i20', 'Tata Tiago', 'Maruti Suzuki WagonR'])[1 + g % 4]
                 WHEN g <= 19 THEN (ARRAY['Honda City', 'Hyundai Verna', 'Skoda Slavia'])[1 + g % 3]
                 WHEN g <= 23 THEN (ARRAY['Toyota Innova Crysta', 'Maruti Suzuki Ertiga', 'Kia Carens'])[1 + g % 3]
                 WHEN g <= 26 THEN (ARRAY['Toyota Camry', 'Skoda Superb', 'Mercedes-Benz E-Class'])[1 + g % 3]
                 WHEN g <= 39 THEN (ARRAY['Hero Splendor Plus', 'Honda Shine', 'TVS Raider', 'Bajaj Pulsar 150'])[1 + g % 4]
                 ELSE (ARRAY['Bajaj RE', 'TVS King', 'Piaggio Ape City'])[1 + g % 3]
             END AS vehicle_model
      FROM generate_series(2, 50) AS g) v
WHERE d.user_id = v.user_id AND d.simulated AND d.status <> 'ON_TRIP';
