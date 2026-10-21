-- Trip paths are kept for rydex.tracking.history-retention (LocationHistoryRetention): finding the
-- old ones needs an index on the time alone.
CREATE INDEX ix_location_history_recorded_at ON driver_location_history (recorded_at);
