-- Driver profiles for the seeded DRIVER users of ride-service (same fixed ids:
-- 00000000-0000-4000-8000-0000000002NN, driver01..50@rydex.dev).
-- 12 ECONOMY, 7 COMFORT, 4 XL, 3 PREMIUM, 13 BIKE, 11 AUTO (ADR 0018). Everyone starts OFFLINE;
-- positions live in Redis only.
CREATE TEMPORARY TABLE seed_fleet ON COMMIT DROP AS
SELECT g,
       ('00000000-0000-4000-8000-' || lpad((200 + g)::text, 12, '0'))::uuid AS user_id,
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
FROM generate_series(1, 50) AS g;

INSERT INTO drivers (user_id, name, vehicle_type, vehicle_number, vehicle_model, rating)
SELECT user_id,
       'Driver ' || lpad(g::text, 2, '0'),
       vehicle_type,
       'TN ' || lpad((1 + g % 22)::text, 2, '0') || ' '
             || chr(65 + g % 26) || chr(65 + (g * 7) % 26) || ' ' || (1000 + g * 37)::text,
       vehicle_model,
       4.50 + ((g * 37) % 50) / 100.0
FROM seed_fleet
ON CONFLICT DO NOTHING;

-- The living city (ADR 0016): driver01 is the owner's to drive by hand; drivers 02-50 are NPCs.
UPDATE drivers SET simulated = true
WHERE user_id BETWEEN '00000000-0000-4000-8000-000000000202' AND '00000000-0000-4000-8000-000000000250'
  AND NOT simulated;

-- An existing database keeps its NPC fleet (drivers 02-50) in this mix too; a driver on a trip is
-- left alone until the seed runs again. driver01 keeps whatever its owner chose.
UPDATE drivers d
SET vehicle_type = f.vehicle_type, vehicle_model = f.vehicle_model, updated_at = now()
FROM seed_fleet f
WHERE d.user_id = f.user_id AND f.g >= 2 AND d.simulated AND d.status <> 'ON_TRIP'
  AND (d.vehicle_type, d.vehicle_model) IS DISTINCT FROM (f.vehicle_type, f.vehicle_model);
