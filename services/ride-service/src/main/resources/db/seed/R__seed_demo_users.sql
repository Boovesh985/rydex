-- Demo accounts for local development and the public demo (not loaded when
-- FLYWAY_LOCATIONS excludes classpath:db/seed).
--   admin@rydex.dev, rider1..60@rydex.dev, driver01..50@rydex.dev
--   password for all: Rydex@123
-- Living city (ADR 0016): rider1 and driver01 are for manual testing; riders 2-60 and drivers
-- 02-50 are automated (NPC) accounts the simulator plays.
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
       CASE WHEN g = 1 THEN 'Rider 1' ELSE (ARRAY[
           'Asha Raman', 'Karthik S', 'Priya Natarajan', 'Vignesh Kumar', 'Divya Iyer', 'Arun Prakash',
           'Meena Sundaram', 'Rahul Menon', 'Lakshmi Venkat', 'Sanjay Rao', 'Nithya Balaji', 'Harish M',
           'Kavya Reddy', 'Suresh Babu', 'Anjali Nair', 'Gokul Krishnan', 'Revathi P', 'Manoj Pillai',
           'Swetha Ganesh', 'Prakash Raj', 'Deepa Mohan', 'Ajith Kumar', 'Sowmya R', 'Vijay Anand',
           'Janani S', 'Bharath V', 'Keerthana M', 'Dinesh Kannan', 'Pooja Shankar', 'Senthil N',
           'Abirami K', 'Naveen Raj', 'Gayathri Devi', 'Ashwin R', 'Hema Malini S', 'Rajesh T',
           'Shalini Joseph', 'Mohan Das', 'Uma Maheswari', 'Siddharth B', 'Archana V', 'Ganesh Moorthy',
           'Ramya Krishnan', 'Pradeep S', 'Bhavana Rao', 'Saravanan P', 'Nandhini A', 'Kishore K',
           'Vidya Subramanian', 'Arjun Mehta', 'Indhu R', 'Murali Dharan', 'Sangeetha L', 'Tarun J',
           'Preethi Anand', 'Elango S', 'Monisha D', 'Sathish Kumar', 'Yamini Prasad'])[g - 1] END,
       'rider' || g || '@rydex.dev',
       '+91910000' || lpad(g::text, 4, '0'),
       '$2a$10$HpvoLmSqzF3cri4GvWCEou2dXSZYLg1n1G8lXjzY.sK8mUGyEEomq',
       'RIDER'
FROM generate_series(1, 60) AS g
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

-- NPCs: everyone but rider1 and driver01 (and the admin)
UPDATE users SET simulated = true
WHERE id BETWEEN '00000000-0000-4000-8000-000000000102' AND '00000000-0000-4000-8000-000000000160'
   OR id BETWEEN '00000000-0000-4000-8000-000000000202' AND '00000000-0000-4000-8000-000000000250';
