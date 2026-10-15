-- The living city (ADR 0016): which drivers are NPCs (driven by the simulator), and whether a ride's
-- rider is one, so matching can offer rides to the real people online before the NPCs.
ALTER TABLE drivers ADD COLUMN simulated boolean NOT NULL DEFAULT false;
ALTER TABLE match_jobs ADD COLUMN simulated_rider boolean NOT NULL DEFAULT false;
