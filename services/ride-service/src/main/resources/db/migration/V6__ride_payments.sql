-- Phase 5 (ADR 0012): late-cancellation fee, and the ride's payment as payment-service reports it.
ALTER TABLE rides
    ADD COLUMN cancellation_fee numeric(10, 2),
    ADD COLUMN payment_status   varchar(10),
    ADD COLUMN refunded_amount  numeric(10, 2);
