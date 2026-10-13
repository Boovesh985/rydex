# 12. Payments: mock provider, Razorpay test mode, refunds

Date: 2026-10-13 · Status: Accepted

## Context
Phase 5 adds payment-service (master prompt §4, §17):
- charge the rider when a ride completes;
- support a **mock provider** (the default) and Razorpay **test mode**, both free;
- refund payments;
- publish `payment.events` for ride-service and tracking-service.

Rydex takes no real money. Razorpay test mode behaves like production (orders, Checkout, signatures, webhooks, refunds) but every payment is simulated.

## Decision

### When a rider pays
- **Completed ride:** the rider pays the fare. Pricing is upfront, so the fare is the quote (ADR 0007).
- **Late cancellation:** a rider who cancels *after the driver arrived* pays a fee per ride type (`rydex.rides.cancellation-fees`: ECONOMY Rs 50, COMFORT Rs 75, XL Rs 100).
  - Earlier cancellations are free, as are cancellations by the driver, an admin or the system.
  - ride-service decides the fee and puts it in RIDE_CANCELLED (`cancellationFee`); payment-service only charges what it is told.
- A ride ends exactly once, completed or cancelled, so **a ride has at most one payment** (`payments.ride_id` is unique). Redelivered or stray events never charge twice.

### Refunds
- Admins refund all or part of a succeeded payment (`POST /api/admin/payments/{id}/refunds`, with a reason).
  - This is how a disputed cancellation fee (the "paid cancellation" of the master prompt) or a bad trip is refunded.
  - Nothing is refunded automatically: under these rules a ride cannot be cancelled after it was paid, so there is no automatic case.
- A refund's amount is reserved on the payment (`refunding_amount`) until the provider confirms it.
  - Optimistic locking and a database CHECK make sure refunds can never add up to more than was paid.
- The payment is REFUNDED once refunded in full. A partial refund leaves it SUCCEEDED, with `refundedAmount`.

### Providers
- **Mock:** charges and refunds succeed at once, with no network calls. Charges above `rydex.payments.mock.decline-above` are declined, so failures can be exercised.
- **Razorpay test mode:** a charge is a Razorpay **order**.
  - The rider pays it in Razorpay Checkout in the browser (card, UPI and netbanking, all simulated).
  - A server cannot charge a rider by itself without saved-card approval, so the money moves only when the rider acts. PAYMENT_INITIATED tells the rider's app to open Checkout.
- The provider is picked by `PAYMENT_PROVIDER`.
  - A payment is always finished, checked and refunded by the provider that started it.
  - Razorpay is configured whenever keys are present, even while new payments go to the mock.
- **Test mode only:** a key that is not `rzp_test_...` stops the service at startup unless `allow-live` is set. Missing keys with `PAYMENT_PROVIDER=razorpay` stop it too, with a message that says which variable is missing.
- **Secrets:** locally they live in a git-ignored `services/payment-service/.env`, read as properties (`spring.config.import: optional:file:.env[.properties]`); in containers they are real environment variables. The key secret and webhook secret never leave the server; only the public key id goes to Checkout.

### How Rydex learns that a rider paid
Three ways. All of them record only what Razorpay confirms, and recording is idempotent, so whichever arrives first wins and the others do nothing.
1. **Checkout callback:** the browser forwards `razorpay_order_id`, `razorpay_payment_id` and `razorpay_signature` to `POST /api/payments/{id}/razorpay/verify`. The service then:
   - checks the HMAC-SHA256 signature with the key secret (constant-time comparison);
   - checks that the order is this payment's;
   - fetches the payment from Razorpay, which must be captured, or authorized (and is then captured here) for the full amount.
2. **Webhook:** `POST /api/payments/webhooks/razorpay`, signed with the webhook secret, handles `payment.captured`, `payment.failed`, `refund.processed` and `refund.failed`.
   - Unknown orders are acknowledged, so Razorpay stops retrying them.
   - It is optional: Razorpay can only reach the backend once it is public (Phase 10).
3. **Polling:** the processor asks Razorpay about unpaid orders. It checks after 30 s, then ever less often up to every 30 min, and stops after 7 days. This covers a closed tab or a missing webhook.

A failed Checkout attempt marks the payment FAILED (PAYMENT_FAILED notifies the rider), but the order stays open. A later successful attempt still moves the payment to SUCCEEDED.

### Provider calls happen outside transactions
- A `PaymentProcessor` moves payments and refunds forward:
  - it starts new work right after its transaction commits;
  - a sweep every 2 s picks up retries, scheduled checks and anything a crash interrupted.
- Each row is claimed with a short lease (`lease_until`, 30 s), so one worker on any instance talks to the provider about it at a time. Provider calls never hold a database transaction open.
- Outcomes are written by `PaymentLedger`, each in one transaction with its outbox event.
- An unreachable provider (network error, 5xx, 429, or 401 for bad keys) is retried with backoff (1 s doubling to 5 min). Other 4xx are final declines.
- **Refunds cannot be doubled by a lost response:**
  - each Razorpay refund carries Rydex's refund id in its notes;
  - a retried refund first lists the payment's refunds on Razorpay and adopts a match instead of creating another.
  - An order created twice after a crash is harmless: nobody pays the orphan.

### Events and their readers
- `payment.events` uses the outbox (ADR 0009), keyed by ride id, so a ride's payment events stay in order.
  - Events: PAYMENT_INITIATED (Razorpay only: the rider must pay), PAYMENT_SUCCEEDED, PAYMENT_FAILED, PAYMENT_REFUNDED.
  - Schema: [contracts/events/payment-payload.schema.json](../../contracts/events/payment-payload.schema.json).
- ride-service mirrors the status on the ride (`paymentStatus`, `refundedAmount`), so ride history shows what is paid.
- tracking-service notifies the rider at every step, and the driver when a fare or fee is paid ([contracts/websocket.md](../../contracts/websocket.md)).

### Testing Razorpay before the frontend
`/dev/checkout.html` on payment-service loads Razorpay Checkout for a ride; the smoke test opens it with `-PayInBrowser`. It is enabled locally and will be switched off in production (`PAYMENT_DEV_PAGES=false`, Phase 8). Tests run against an in-memory Razorpay stub, never against Razorpay itself.

## Consequences
- **Not every test payment method can be refunded.** In the first run against a real test account, Razorpay rejected the refund of a wallet payment with only "invalid request sent". The same request with an amount that was too large was rejected for that reason instead, which shows the request itself was valid. A netbanking payment was refunded normally. Rydex records such a refund as FAILED with Razorpay's reason and releases the reserved amount, so an admin can try again.
- **No automatic charge with Razorpay.** A rider who never opens Checkout stays unpaid; ride-service shows `paymentStatus: PENDING`. There is no dunning in this demo.
- **Mock never sends PAYMENT_INITIATED.** Consumers must not wait for it.
- **Webhooks need a public URL.** Until Phase 10, a payment made with the tab closed is noticed by polling, within the check interval.
- **Metrics:** `payments_succeeded_total`, `payment_failures_total` (master prompt §14) and `payment_refunds_total`, each by provider and kind. Grafana uses them in Phase 9.
