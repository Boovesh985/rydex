-- A driver's payment history (GET /api/payments/mine) is read newest first, like a rider's.
CREATE INDEX ix_payments_driver ON payments (driver_id, created_at DESC) WHERE driver_id IS NOT NULL;
