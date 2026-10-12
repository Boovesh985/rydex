-- Driver profiles for the seeded DRIVER users of ride-service (same fixed ids:
-- 00000000-0000-4000-8000-0000000002NN, driver01..50@rydex.dev).
-- 30 ECONOMY, 12 COMFORT, 8 XL. Everyone starts OFFLINE; positions live in Redis only.
INSERT INTO drivers (user_id, name, vehicle_type, vehicle_number, vehicle_model, rating)
SELECT ('00000000-0000-4000-8000-' || lpad((200 + g)::text, 12, '0'))::uuid,
       'Driver ' || lpad(g::text, 2, '0'),
       CASE WHEN g <= 30 THEN 'ECONOMY' WHEN g <= 42 THEN 'COMFORT' ELSE 'XL' END,
       'TN ' || lpad((1 + g % 22)::text, 2, '0') || ' '
             || chr(65 + g % 26) || chr(65 + (g * 7) % 26) || ' ' || (1000 + g * 37)::text,
       CASE
           WHEN g <= 30 THEN (ARRAY['Maruti Suzuki Swift', 'Hyundai i20', 'Tata Tiago', 'Maruti Suzuki WagonR'])[1 + g % 4]
           WHEN g <= 42 THEN (ARRAY['Honda City', 'Hyundai Verna', 'Skoda Slavia'])[1 + g % 3]
           ELSE (ARRAY['Toyota Innova Crysta', 'Maruti Suzuki Ertiga', 'Kia Carens'])[1 + g % 3]
       END,
       4.50 + ((g * 37) % 50) / 100.0
FROM generate_series(1, 50) AS g
ON CONFLICT DO NOTHING;
