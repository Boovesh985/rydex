-- The unmatched-ride sweeper looks for rides still REQUESTED long after booking (ADR 0010).
CREATE INDEX ix_rides_requested_at ON rides (requested_at) WHERE status = 'REQUESTED';
