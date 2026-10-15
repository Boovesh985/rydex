-- Living city (ADR 0016): the automated (NPC) riders and drivers the simulator plays.
-- Rides booked by an NPC rider carry the flag in their events, so payment-service charges them
-- through the mock provider and never opens real (test-mode) gateway orders for them.
ALTER TABLE users ADD COLUMN simulated boolean NOT NULL DEFAULT false;
ALTER TABLE rides ADD COLUMN simulated boolean NOT NULL DEFAULT false;
