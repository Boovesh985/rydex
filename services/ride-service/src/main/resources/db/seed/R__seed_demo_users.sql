-- Demo accounts for local development and the public demo (not loaded when
-- FLYWAY_LOCATIONS excludes classpath:db/seed).
--   admin@rydex.dev, rider1..5@rydex.dev, driver01..50@rydex.dev
--   password for all: Rydex@123
-- Ids are fixed so driver-service can seed matching driver profiles:
--   admin   00000000-0000-4000-8000-000000000001
--   riders  00000000-0000-4000-8000-0000000001NN
--   drivers 00000000-0000-4000-8000-0000000002NN
INSERT INTO users (id, name, email, phone, password_hash, role)
VALUES ('00000000-0000-4000-8000-000000000001', 'Rydex Admin', 'admin@rydex.dev', '+919000000001',
        '$2a$10$HpvoLmSqzF3cri4GvWCEou2dXSZYLg1n1G8lXjzY.sK8mUGyEEomq', 'ADMIN')
ON CONFLICT DO NOTHING;

INSERT INTO users (id, name, email, phone, password_hash, role)
SELECT ('00000000-0000-4000-8000-' || lpad((100 + g)::text, 12, '0'))::uuid,
       'Rider ' || g,
       'rider' || g || '@rydex.dev',
       '+91910000' || lpad(g::text, 4, '0'),
       '$2a$10$HpvoLmSqzF3cri4GvWCEou2dXSZYLg1n1G8lXjzY.sK8mUGyEEomq',
       'RIDER'
FROM generate_series(1, 5) AS g
ON CONFLICT DO NOTHING;

INSERT INTO users (id, name, email, phone, password_hash, role)
SELECT ('00000000-0000-4000-8000-' || lpad((200 + g)::text, 12, '0'))::uuid,
       'Driver ' || lpad(g::text, 2, '0'),
       'driver' || lpad(g::text, 2, '0') || '@rydex.dev',
       '+91920000' || lpad(g::text, 4, '0'),
       '$2a$10$HpvoLmSqzF3cri4GvWCEou2dXSZYLg1n1G8lXjzY.sK8mUGyEEomq',
       'DRIVER'
FROM generate_series(1, 50) AS g
ON CONFLICT DO NOTHING;
