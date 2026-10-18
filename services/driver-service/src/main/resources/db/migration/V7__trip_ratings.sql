-- How many trip ratings a driver's rating is the average of. The seeded demo drivers' ratings stand
-- for about 20 earlier trips each, so one new rating moves them a little, not all the way.
ALTER TABLE drivers ADD COLUMN rating_count integer NOT NULL DEFAULT 0;
UPDATE drivers SET rating_count = 20
WHERE user_id BETWEEN '00000000-0000-4000-8000-000000000201' AND '00000000-0000-4000-8000-000000000250';
