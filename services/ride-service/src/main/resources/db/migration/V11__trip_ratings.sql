-- The rider's rating of a completed trip (once): stars, and an optional comment.
ALTER TABLE rides ADD COLUMN rider_rating  integer CHECK (rider_rating BETWEEN 1 AND 5);
ALTER TABLE rides ADD COLUMN rider_comment varchar(280);
ALTER TABLE rides ADD COLUMN rated_at      timestamptz;
