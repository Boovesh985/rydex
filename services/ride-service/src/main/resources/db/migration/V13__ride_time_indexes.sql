-- The living city books about 4,000 rides a day around the clock. The admin dashboard (AdminStats,
-- every few seconds) and the sweepers filter rides by time and by the statuses still in flight; without
-- these indexes each of them would scan the whole table.
CREATE INDEX ix_rides_requested ON rides (requested_at);
CREATE INDEX ix_rides_in_flight ON rides (status)
    WHERE status IN ('REQUESTED', 'DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_PROGRESS');
CREATE INDEX ix_rides_completed ON rides (completed_at) WHERE status = 'COMPLETED';
CREATE INDEX ix_rides_cancelled ON rides (cancelled_at) WHERE status = 'CANCELLED';
